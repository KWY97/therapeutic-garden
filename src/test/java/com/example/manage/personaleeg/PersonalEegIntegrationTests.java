package com.example.manage.personaleeg;

import com.example.manage.domain.*;
import com.example.manage.dto.MeasurementHistoryView;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ui.ExtendedModelMap;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Each test uses an isolated in-memory DB. No local or Production MySQL connection is made. */
class PersonalEegIntegrationTests {
    @TempDir Path temporary;
    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach void database() throws Exception {
        url="jdbc:h2:mem:personal_eeg_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        for(String sql:List.of(
                "create table site (site_id bigint primary key)",
                "create table member (member_id bigint primary key, participant_no int not null unique, test_account boolean not null default false)",
                "create table healing_course (course_id bigint primary key, site_id bigint not null, code varchar(32) not null)",
                "create table healing_spot (spot_id bigint primary key, course_id bigint not null, code varchar(32) not null, name varchar(255))",
                "create table healing_measurement_import_batch (id bigint primary key)",
                "create table healing_measurement_session (id bigint primary key, batch_id bigint, source_row int, member_id bigint, course_id bigint, measurement_date date, baseline_stress decimal(38,18), baseline_emotional decimal(38,18))",
                "create table healing_spot_measurement (id bigint primary key, session_id bigint, spot_id bigint, stress_post decimal(38,18), emotional_post decimal(38,18))",
                "create table healing_spot_effect_summary (spot_id bigint primary key, stress_improved_count int)",
                "create table member_healing_spot_effect_summary (member_id bigint, spot_id bigint, stress_improved_count int, primary key(member_id,spot_id))"
        )) jdbc.execute(sql);
        jdbc.update("insert into site values (1)");
        for(int p=1;p<=11;p++) jdbc.update("insert into member (member_id,participant_no) values (?,?)",p,p);
        for(int c=1;c<=3;c++) jdbc.update("insert into healing_course values (?,?,?)",c,1,"HC-"+(char)('A'+c-1));
        for(int s=1;s<=6;s++) jdbc.update("insert into healing_spot values (?,?,?,?)",s,(s-1)/2+1,"HS"+s,"Spot "+s);
        jdbc.update("insert into healing_spot_effect_summary values (1,17)");
        jdbc.update("insert into member_healing_spot_effect_summary values (1,1,5)");
        jdbc.update("insert into healing_measurement_import_batch values (1)");
        jdbc.update("insert into healing_measurement_session values (1,1,2,1,1,DATE '2026-08-21',10,5)");
        jdbc.update("insert into healing_spot_measurement values (1,1,1,8,6)");
        String migration=Files.readString(Path.of("docs/personal-eeg-analysis-schema.sql"));
        String statements=migration.lines().filter(line->!line.stripLeading().startsWith("--"))
                .reduce("",(a,b)->a+b+"\n");
        for(String sql:statements.split(";")) if(!sql.isBlank()) jdbc.execute(sql);
    }

    @Test void actualPrivateReportDryRunAndIsolatedDraftImportPreserveLegacyTables() throws Exception {
        Path file=Path.of(".local/egg-report/EEG_Personal_Report_All_Participants.xlsx");
        Assumptions.assumeTrue(Files.isRegularFile(file),"private workbook is not shipped with the repository");
        var source=new PersonalEegExcelParser().parse(file);
        assertEquals(65,source.sessions().size()); assertEquals(130,source.resultCount());
        assertEquals(126,source.completeCount()); assertEquals(4,source.postMissingCount());
        assertEquals(1,source.correctedDateCount());
        var p008=source.sessions().stream()
                .filter(s->s.participantNo()==8 && s.courseCode().equals("HC-B"))
                .filter(s->s.sourceDate().equals(LocalDate.of(2026,1,26))).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026,9,4),p008.confirmedDate());
        assertEquals(Set.of("3:2026-08-19:HS2","3:2026-08-24:HS4",
                        "4:2026-08-26:HS5","5:2026-08-14:HS3"),
                missingKeys(source));
        var importer=new PersonalEegDatabaseImporter();
        try(var c=connect()) {
            var dry=importer.execute(c,source,1,"eeg-2026-v1",true);
            assertEquals("DRY_RUN",dry.status());
        }
        assertEquals(0,count("personal_eeg_analysis_batch"));
        try(var c=connect()) { assertEquals("IMPORTED_DRAFT",importer.execute(c,source,1,"eeg-2026-v1",false).status()); }
        assertEquals(65,count("personal_eeg_analysis_session"));
        assertEquals(130,count("personal_eeg_spot_result"));
        assertEquals(4,jdbc.queryForObject("select count(*) from personal_eeg_spot_result where stress_post is null and emotional_post is null",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from personal_eeg_analysis_session x join member m on m.member_id=x.member_id join healing_course c on c.course_id=x.course_id where m.participant_no=8 and c.code='HC-B' and x.source_date=DATE '2026-01-26' and x.confirmed_date=DATE '2026-09-04'",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name)='personal_eeg_unmatched_slot'",Integer.class));
        assertStoredMetricsEqualSource(source);
        try(var c=connect()) { assertEquals("ALREADY_IMPORTED",importer.execute(c,source,1,"eeg-2026-v1",false).status()); }
        try(var c=connect()) { assertThrows(IllegalArgumentException.class,
                ()->importer.execute(c,new PersonalEegSource("d".repeat(64),"new.xlsx",source.sessions()),1,"eeg-2026-v1",false)); }
        assertEquals(1,count("personal_eeg_analysis_batch"));
        assertEquals(1,count("healing_measurement_session"));
        assertEquals(1,count("healing_spot_measurement"));
        assertEquals(17,jdbc.queryForObject("select stress_improved_count from healing_spot_effect_summary where spot_id=1",Integer.class));
        assertEquals(5,jdbc.queryForObject("select stress_improved_count from member_healing_spot_effect_summary where member_id=1 and spot_id=1",Integer.class));
        assertEquals(0,count("personal_eeg_active_version"));
        var versions=new PersonalEegVersionStore();
        try(var c=connect()) { versions.activate(c,1); }
        assertEquals(1,count("personal_eeg_active_version"));
        var p008History=new PersonalEegMeasurementRepository(jdbc).findActiveMember(8L).orElseThrow();
        assertTrue(p008History.rows().stream().anyMatch(r->r.measurementDate().equals(LocalDate.of(2026,9,4))
                && r.courseCode().equals("HC-B")));
        var missingPost=new PersonalEegMeasurementRepository(jdbc).findActiveMember(3L).orElseThrow().rows().stream()
                .filter(r->r.measurementDate().equals(LocalDate.of(2026,8,19)) && r.spotId()==2L)
                .findFirst().orElseThrow();
        var missingView=MeasurementHistoryView.Record.from(missingPost);
        assertNotNull(missingView.stress().baseline());
        assertNull(missingView.stress().post());
        assertEquals("계산 불가",missingView.stress().changeDisplay());
        assertEquals("계산 불가",missingView.emotional().rateDisplay());
        try(var c=connect()) { versions.returnToLegacy(c); }
        assertTrue(new PersonalEegMeasurementRepository(jdbc).findActiveMember(8L).isEmpty());
    }

    @Test void inactiveFallbackActivationSwitchRollbackAndSharedMemberAdminModel() throws Exception {
        var personalRepo=new PersonalEegMeasurementRepository(jdbc);
        var legacyRepo=new HealingMeasurementRepository(jdbc);
        var spots=mock(HealingSpotRepository.class);
        var entities=new ArrayList<HealingSpot>();
        for(int n=1;n<=6;n++) {
            var spot=mock(HealingSpot.class); when(spot.getSpotId()).thenReturn((long)n);
            when(spot.getCode()).thenReturn("HS"+n); when(spot.getName()).thenReturn("Spot "+n); entities.add(spot);
        }
        when(spots.findByHealingCourseSiteSiteId(1L)).thenReturn(entities);
        var batches=mock(HealingEffectImportBatchRepository.class);
        var oldBatch=mock(HealingEffectImportBatch.class); when(oldBatch.getTargetSiteId()).thenReturn(1L);
        when(batches.findFirstByOrderByIdAsc()).thenReturn(Optional.of(oldBatch));
        var history=new MeasurementHistoryQueryService(legacyRepo,spots,batches,personalRepo);
        assertEquals(new BigDecimal("10.000000000000000000"),history.findImportedMember(1L).getFirst().records().getFirst().stress().baseline());
        var importer=new PersonalEegDatabaseImporter();
        try(var c=connect()) { importer.execute(c,synthetic("a".repeat(64),"1.5"),1,"eeg-a",false); }
        assertEquals(new BigDecimal("10.000000000000000000"),history.findImportedMember(1L).getFirst().records().getFirst().stress().baseline());
        try(var c=connect()) { importer.execute(c,synthetic("b".repeat(64),"1.2"),1,"eeg-b",false); }
        var versions=new PersonalEegVersionStore();
        try(var c=connect()) { versions.activate(c,1); }
        var first=history.findImportedMember(1L).getFirst().records().getFirst();
        assertEquals("2026-08-21",first.measurementDate());
        assertEquals("HC-A",first.courseCode());
        assertEquals(new BigDecimal("2.000000000000000000"),first.stress().baseline());
        assertEquals(new BigDecimal("1.500000000000000000"),first.stress().post());
        assertEquals(new BigDecimal("-0.500000000000000000"),first.stress().change());
        assertEquals("25.0% 감소",first.stress().rateDisplay());
        assertEquals("20.0% 증가",first.emotional().rateDisplay());
        assertNull(history.findImportedMember(1L).get(1).records().getFirst().stress().post());
        assertEquals("계산 불가",history.findImportedMember(1L).get(1).records().getFirst().stress().rateDisplay());

        var effects=mock(HealingEffectQueryService.class);
        when(effects.findImportedMemberImprovements(1L)).thenReturn(List.of());
        when(effects.findImportedMemberOverallImprovement(1L)).thenReturn(Optional.empty());
        var members=mock(MemberRepository.class);
        var member=mock(Member.class); when(member.isTestAccount()).thenReturn(false);
        when(members.findById(1L)).thenReturn(Optional.of(member));
        var modelService=new MemberAnalysisModelService(effects,history,batches,members);
        var memberModel=new ExtendedModelMap(); var adminModel=new ExtendedModelMap();
        modelService.populateForLoggedInMember(1L,"P001",memberModel);
        modelService.populate(1L,"P001",adminModel);
        assertEquals(memberModel.get("measurementHistory"),adminModel.get("measurementHistory"));
        assertEquals(17,jdbc.queryForObject("select stress_improved_count from healing_spot_effect_summary where spot_id=1",Integer.class));

        try(var c=connect()) { versions.activate(c,2); }
        assertEquals(new BigDecimal("1.200000000000000000"),history.findImportedMember(1L).getFirst().records().getFirst().stress().post());
        try(var c=connect()) { versions.activate(c,1); }
        assertEquals(new BigDecimal("1.500000000000000000"),history.findImportedMember(1L).getFirst().records().getFirst().stress().post());
        try(var c=connect()) { versions.returnToLegacy(c); }
        assertEquals(new BigDecimal("10.000000000000000000"),history.findImportedMember(1L).getFirst().records().getFirst().stress().baseline());
        assertEquals(1,count("healing_measurement_session")); assertEquals(1,count("healing_spot_measurement"));
        assertEquals(2,count("personal_eeg_analysis_batch"));
    }

    @Test void activePersonalHistoryUsesMeasurementDatesRatherThanSourceRows() throws Exception {
        var first=synthetic("c".repeat(64),"1.5").sessions().getFirst();
        var earlier=new PersonalEegSource.Session(3,1,LocalDate.of(2026,8,14),LocalDate.of(2026,8,14),
                "HC-A",first.spots());
        var source=new PersonalEegSource("c".repeat(64),"synthetic.xlsx",List.of(first,earlier));
        try(var c=connect()) { new PersonalEegDatabaseImporter().execute(c,source,1,"eeg-sort",false); }
        try(var c=connect()) { new PersonalEegVersionStore().activate(c,1); }
        var personal=new PersonalEegMeasurementRepository(jdbc).findActiveMember(1L).orElseThrow();
        assertEquals(List.of(LocalDate.of(2026,8,14),LocalDate.of(2026,8,21)),
                personal.rows().stream().filter(r->r.spotId()==1L).map(HealingMeasurementRecord::measurementDate).toList());
    }

    @Test void fourReportedValuesDoNotRequireTheEegBandSheet() throws Exception {
        Path original=Path.of(".local/egg-report/EEG_Personal_Report_All_Participants.xlsx");
        Assumptions.assumeTrue(Files.isRegularFile(original),"private workbook is not shipped with the repository");
        Path withoutBands=temporary.resolve("dated-only.xlsx");
        try(var workbook=new XSSFWorkbook(Files.newInputStream(original))) {
            int index=workbook.getSheetIndex("EEG_날짜별_대역균형");
            assertTrue(index>=0);
            workbook.removeSheetAt(index);
            try(var output=Files.newOutputStream(withoutBands)) { workbook.write(output); }
        }
        var source=new PersonalEegExcelParser().parse(withoutBands);
        assertEquals(65,source.sessions().size());
        assertEquals(130,source.resultCount());
        assertEquals(4,source.postMissingCount());
        assertEquals(1,source.correctedDateCount());
    }

    @Test void correctedP008DateCannotCollideWithAnotherSession() throws Exception {
        Path original=Path.of(".local/egg-report/EEG_Personal_Report_All_Participants.xlsx");
        Assumptions.assumeTrue(Files.isRegularFile(original),"private workbook is not shipped with the repository");
        Path duplicateFile=temporary.resolve("date-collision.xlsx");
        try(var workbook=new XSSFWorkbook(Files.newInputStream(original))) {
            var sheet=workbook.getSheet("날짜별_결과");
            var sourceRow=java.util.stream.IntStream.rangeClosed(1,sheet.getLastRowNum())
                    .mapToObj(sheet::getRow)
                    .filter(row->row!=null && "P008".equals(row.getCell(0).getStringCellValue())
                            && "2026-01-26".equals(row.getCell(1).getStringCellValue())
                            && "HC-B".equals(row.getCell(2).getStringCellValue()))
                    .findFirst().orElseThrow();
            var duplicate=sheet.createRow(sheet.getLastRowNum()+1);
            for(var cell:sourceRow) {
                var copy=duplicate.createCell(cell.getColumnIndex(),cell.getCellType());
                if(cell.getCellType()==CellType.STRING) copy.setCellValue(cell.getStringCellValue());
                else if(cell.getCellType()==CellType.NUMERIC) copy.setCellValue(cell.getNumericCellValue());
            }
            duplicate.getCell(1).setCellValue("2026-09-04");
            try(var output=Files.newOutputStream(duplicateFile)) { workbook.write(output); }
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                ()->new PersonalEegExcelParser().parse(duplicateFile)).getMessage().contains("정정 후"));
    }

    private PersonalEegSource synthetic(String sha,String hs1Post) {
        var date=LocalDate.of(2026,8,21);
        var spots=List.of(
                new PersonalEegSource.Spot("HS1",new BigDecimal("2"),new BigDecimal(hs1Post),new BigDecimal("1"),new BigDecimal("1.2")),
                new PersonalEegSource.Spot("HS2",new BigDecimal("2"),null,new BigDecimal("1"),null));
        return new PersonalEegSource(sha,"synthetic.xlsx",List.of(
                new PersonalEegSource.Session(2,1,date,date,"HC-A",spots)));
    }
    private static Set<String> missingKeys(PersonalEegSource source) {
        var keys=new HashSet<String>();
        for(var session:source.sessions()) for(var spot:session.spots())
            if(spot.stressPost()==null)
                keys.add(session.participantNo()+":"+session.sourceDate()+":"+spot.code());
        return keys;
    }
    private void assertStoredMetricsEqualSource(PersonalEegSource source) {
        record Stored(int participant,LocalDate sourceDate,LocalDate confirmedDate,String course,String spot,
                      BigDecimal baselineStress,BigDecimal stressPost,BigDecimal baselineEmotional,BigDecimal emotionalPost) {}
        var rows=jdbc.query("""
                select m.participant_no,x.source_date,x.confirmed_date,c.code,h.code,
                       p.baseline_stress,p.stress_post,p.baseline_emotional,p.emotional_post
                from personal_eeg_spot_result p
                join personal_eeg_analysis_session x on x.id=p.session_id
                join member m on m.member_id=x.member_id
                join healing_course c on c.course_id=x.course_id
                join healing_spot h on h.spot_id=p.spot_id
                """,(r,n)->new Stored(r.getInt(1),r.getDate(2).toLocalDate(),r.getDate(3).toLocalDate(),
                r.getString(4),r.getString(5),r.getBigDecimal(6),r.getBigDecimal(7),
                r.getBigDecimal(8),r.getBigDecimal(9)));
        assertEquals(130,rows.size());
        var byKey=new HashMap<String,Stored>();
        for(var row:rows) assertNull(byKey.put(row.participant()+":"+row.sourceDate()+":"+row.course()+":"+row.spot(),row));
        for(var session:source.sessions()) for(var spot:session.spots()) {
            var row=byKey.get(session.participantNo()+":"+session.sourceDate()+":"+session.courseCode()+":"+spot.code());
            assertNotNull(row);
            assertEquals(session.confirmedDate(),row.confirmedDate());
            assertSameNumber(spot.baselineStress(),row.baselineStress());
            assertSameNumber(spot.stressPost(),row.stressPost());
            assertSameNumber(spot.baselineEmotional(),row.baselineEmotional());
            assertSameNumber(spot.emotionalPost(),row.emotionalPost());
        }
    }
    private static void assertSameNumber(BigDecimal expected,BigDecimal actual) {
        if(expected==null) assertNull(actual);
        else { assertNotNull(actual); assertEquals(0,expected.compareTo(actual)); }
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url,"sa",""); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from "+table,Integer.class); }
}
