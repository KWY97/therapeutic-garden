package com.example.manage.healingeffect;

import com.example.manage.dto.MeasurementHistoryView;
import com.example.manage.repository.HealingMeasurementRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RawMeasurementTests {
    @TempDir Path dir;
    Path workbook(String numeric, boolean duplicate, boolean badHeader) throws Exception {
        Path path=dir.resolve("synthetic.xlsx");
        try(var w=new XSSFWorkbook()) {
            var s=w.createSheet(RawMeasurementParser.SHEET);
            var header=s.createRow(0);
            for(int i=0;i<18;i++) header.createCell(i).setCellValue(RawMeasurementParser.HEADERS.get(i));
            if(badHeader) header.getCell(0).setCellValue("wrong");
            for(int i=1;i<=2;i++) {
                var r=s.createRow(i);
                r.createCell(0).setCellValue("P001");
                r.createCell(1).setCellValue(duplicate?"2026-08-14":"2026-08-"+(13+i));
                r.createCell(2).setCellValue("HC-A (A 코스)");
                r.createCell(3).setCellValue("HS1 호스타 정원");
                if(i==1) {r.createCell(4).setCellValue(20);r.createCell(5).setCellValue(10);}
                if(numeric==null) r.createCell(6).setCellValue(15.25); else r.createCell(6).setCellValue(numeric);
                r.createCell(7).setCellValue(12);
            }
            try(var out=Files.newOutputStream(path)) {w.write(out);}
        }
        return path;
    }
    @Test void parserPreservesRecordsMissingBaselineAndSpotMissing() throws Exception {
        var source=new RawMeasurementParser().parse(workbook(null,false,false));
        assertEquals(2,source.sessions().size()); assertEquals(2,source.measurementCount());
        assertEquals(new BigDecimal("15.25"),source.sessions().get(0).spots().get(0).stress());
        assertNull(source.sessions().get(1).baselineStress());
        assertEquals(LocalDate.of(2026,8,14),source.sessions().get(0).date());
        assertEquals(64,source.sha256().length());
    }
    @Test void parserRejectsUnclearSessionsHeadersAndTextNumbers() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new RawMeasurementParser().parse(workbook(null,true,false)));
        assertThrows(IllegalArgumentException.class,()->new RawMeasurementParser().parse(workbook(null,false,true)));
        assertThrows(IllegalArgumentException.class,()->new RawMeasurementParser().parse(workbook("oops",false,false)));
    }
    @Test void metricKeepsSignsZeroMissingAndLargeRates() {
        var b=new BigDecimal("20");
        assertEquals("25.0% 감소",MeasurementHistoryView.Metric.of(b,new BigDecimal("15"),true).rateDisplay());
        assertEquals("25.0% 감소",MeasurementHistoryView.Metric.of(b,new BigDecimal("15"),false).rateDisplay());
        assertEquals("0.0% 변화 없음",MeasurementHistoryView.Metric.of(b,b,true).rateDisplay());
        assertNull(MeasurementHistoryView.Metric.of(null,b,true).change());
        assertNull(MeasurementHistoryView.Metric.of(BigDecimal.ZERO,b,true).rate());
        assertEquals("1423.5% 증가",MeasurementHistoryView.Metric.of(new BigDecimal("1"),new BigDecimal("15.235"),false).rateDisplay());
    }
    DriverManagerDataSource database() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        j.execute("create table site(site_id bigint primary key)");
        j.execute("create table member(member_id bigint primary key,participant_no int)");
        j.execute("create table healing_course(course_id bigint primary key,site_id bigint,code varchar(10))");
        j.execute("create table healing_spot(spot_id bigint primary key,course_id bigint,code varchar(10),name varchar(100))");
        j.update("insert into site values(1)");j.update("insert into member values(1,1)");
        j.update("insert into healing_course values(1,1,'HC-A')");
        j.update("insert into healing_spot values(1,1,'HS1','호스타 정원'),(2,1,'HS2','곶자왈원')");
        return ds;
    }
    void schema(JdbcTemplate j) throws Exception {
        for(String sql:Files.readString(Path.of("docs/healing-measurement-schema.sql")).split(";")) if(!sql.isBlank()) j.execute(sql);
    }
    @Test void importDryRunDuplicateAndExactOrderedQuery() throws Exception {
        var source=new RawMeasurementParser().parse(workbook(null,false,false));
        var ds=database();var j=new JdbcTemplate(ds);var importer=new RawMeasurementDatabaseImporter();
        assertTrue(new HealingMeasurementRepository(j).findMemberForSite(1L,1L).isEmpty());
        try(var c=ds.getConnection()) {assertFalse(importer.execute(c,source,1,true).schemaReady());}
        schema(j);
        try(var c=ds.getConnection()) {assertEquals("DRY_RUN",importer.execute(c,source,1,true).status());}
        assertEquals(0,j.queryForObject("select count(*) from healing_measurement_session",Integer.class));
        try(var c=ds.getConnection()) {assertEquals(2,importer.execute(c,source,1,false).sessions());}
        try(var c=ds.getConnection()) {assertEquals("ALREADY_IMPORTED",importer.execute(c,source,1,false).status());}
        var records=new HealingMeasurementRepository(j).findMemberForSite(1L,1L);
        assertEquals(2,records.size());assertEquals(LocalDate.of(2026,8,14),records.get(0).measurementDate());
        assertEquals(0,new BigDecimal("15.25").compareTo(records.get(0).stressPost()));
        assertNull(records.get(1).baselineStress());
        assertTrue(new HealingMeasurementRepository(j).findMemberForSite(2L,1L).isEmpty());
        assertTrue(new HealingMeasurementRepository(j).findMemberForSite(1L,2L).isEmpty());
        var aggregationRecords=new HealingMeasurementRepository(j).findForSiteAggregation(1L);
        assertEquals(4,aggregationRecords.size());
        assertEquals(2,aggregationRecords.stream().filter(r->r.spotId()==2L).count());
        assertTrue(aggregationRecords.stream().filter(r->r.spotId()==2L)
                .allMatch(r->r.stressPost()==null && !r.stressValid()));
    }
    @Test void mappingsFailBeforeWriteAndSqlFailureRollsBackBatch() throws Exception {
        var source=new RawMeasurementParser().parse(workbook(null,false,false));
        var ds=database();var j=new JdbcTemplate(ds);schema(j);var importer=new RawMeasurementDatabaseImporter();
        j.update("update member set participant_no=2");
        try(var c=ds.getConnection()) {assertThrows(IllegalArgumentException.class,()->importer.execute(c,source,1,false));}
        j.update("update member set participant_no=1");j.update("update healing_course set code='HC-B'");
        try(var c=ds.getConnection()) {assertThrows(IllegalArgumentException.class,()->importer.execute(c,source,1,true));}
        j.update("update healing_course set code='HC-A'");j.update("update healing_spot set name='wrong' where spot_id=1");
        try(var c=ds.getConnection()) {assertThrows(IllegalArgumentException.class,()->importer.execute(c,source,1,true));}
        j.update("update healing_spot set name='호스타 정원' where spot_id=1");
        j.execute("alter table healing_spot_measurement add constraint fail_write check(stress_post < 0)");
        try(var c=ds.getConnection()) {assertThrows(SQLException.class,()->importer.execute(c,source,1,false));}
        for(String table:RawMeasurementDatabaseImporter.TABLES) assertEquals(0,j.queryForObject("select count(*) from "+table,Integer.class));
    }
}
