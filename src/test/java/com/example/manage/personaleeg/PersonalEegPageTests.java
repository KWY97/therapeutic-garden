package com.example.manage.personaleeg;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full Spring Boot templates backed by an isolated H2 database and the private v2 workbook. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:personal-eeg-pages;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonalEegPageTests {
    private static final Path PREVIOUS=Path.of(".local/egg-report/EEG_Personal_Report_All_Participants.xlsx");
    private static final Path CURRENT=Path.of("../private-data/EEG_Personal_Report_All_Participants_v.20261023_ver.2.xlsx");
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired MemberRepository members;
    @Autowired AdminRepository admins;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired PasswordEncoder encoder;
    private MockMvc mvc;
    private final Map<Integer,Long> memberIds=new HashMap<>();
    private long adminId;

    @BeforeAll void seed() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(CURRENT) && Files.isRegularFile(PREVIOUS),
                "private workbooks are not shipped with the repository");
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        var site=sites.saveAndFlush(new Site("EEG isolated", "test", 37.0, 127.0, 3));
        for(int p=1;p<=11;p++) {
            var member=members.saveAndFlush(new Member(p,1,"eeg-page-"+p,encoder.encode("test-password"),
                    String.format("P%03d",p),null));
            memberIds.put(p,member.getMemberId());
        }
        adminId=admins.saveAndFlush(new Admin("eeg-page-admin",encoder.encode("test-password"))).getAdminId();
        for(int c=0;c<3;c++) {
            var course=courses.saveAndFlush(new HealingCourse(site,"HC-"+(char)('A'+c),"Course "+c,null,null,null));
            for(int s=1;s<=2;s++) {
                int number=c*2+s;
                spots.saveAndFlush(new HealingSpot(course,"HS"+number,"Spot "+number,37.0,127.0));
            }
        }
        String schema=Files.readString(Path.of("docs/personal-eeg-analysis-schema.sql"));
        String statements=schema.lines().filter(line->!line.stripLeading().startsWith("--"))
                .reduce("",(a,b)->a+b+"\n");
        for(String sql:statements.split(";")) if(!sql.isBlank()) jdbc.execute(sql);
        var parser=new PersonalEegExcelParser();
        var importer=new PersonalEegDatabaseImporter();
        try(var c=dataSource.getConnection()) { importer.execute(c,parser.parse(PREVIOUS),site.getSiteId(),"eeg-v1",false); }
        try(var c=dataSource.getConnection()) { importer.execute(c,parser.parse(CURRENT),site.getSiteId(),"eeg-v2",false); }
        try(var c=dataSource.getConnection()) { new PersonalEegVersionStore().activate(c,2); }
    }

    @Test void participantAndAdministratorPagesRenderTheSameNewMeasurements() throws Exception {
        var participant=new MockHttpSession();
        participant.setAttribute("loginMemberId",memberIds.get(3));
        participant.setAttribute("loginMemberName","P003");
        var memberPage=mvc.perform(get("/member").session(participant)).andExpect(status().isOk()).andReturn();
        var admin=new MockHttpSession(); admin.setAttribute("loginAdminId",adminId);
        var adminPage=mvc.perform(get("/admin/members/"+memberIds.get(3)+"/data").session(admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(memberPage.getModelAndView().getModel().get("measurementHistory"))
                .isEqualTo(adminPage.getModelAndView().getModel().get("measurementHistory"));
        for(var page:List.of(memberPage,adminPage))
            assertThat(page.getResponse().getContentAsString())
                    .contains("memberMeasurementHistory", "2026-08-19", "2.4653292639234912",
                            "measurement-history.js", "member-healing-analysis.js");
        var p009=new MockHttpSession(); p009.setAttribute("loginMemberId",memberIds.get(9));
        p009.setAttribute("loginMemberName","P009");
        assertThat(mvc.perform(get("/member").session(p009)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("memberMeasurementHistory", "2026-08-21", "HC-C");
    }
}
