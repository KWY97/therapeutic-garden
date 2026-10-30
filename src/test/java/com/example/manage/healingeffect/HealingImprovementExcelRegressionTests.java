package com.example.manage.healingeffect;

import com.example.manage.domain.HealingMeasurementRecord;
import com.example.manage.dto.ImprovementMetricView;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class HealingImprovementExcelRegressionTests {
    @TempDir Path temporary;

    private record Visit(int participant, int course, int day, Double firstStress,
            Double firstEmotional, Double secondStress, Double secondEmotional) {}

    private static final List<Visit> VISITS = List.of(
            new Visit(4, 0, 1, 8.0, 12.0, 10.0, 9.0),
            new Visit(4, 1, 2, null, 12.0, 8.0, null),
            new Visit(4, 2, 3, 12.0, 11.0, 7.0, 10.0),
            new Visit(7, 0, 4, 9.0, 11.0, 8.0, 12.0),
            new Visit(7, 1, 5, 8.0, 9.0, 10.0, 11.0),
            new Visit(7, 2, 6, 7.0, 12.0, null, 9.0));

    @Test void rawAggregationMatchesAllReferenceSpotsAndP004() throws Exception {
        Path raw = createRawWorkbook();
        Path reference = createReferenceWorkbook();

        var source = new RawMeasurementParser().parse(raw);
        assertThat(source.sessions()).hasSize(6);
        var records = new ArrayList<HealingMeasurementRecord>();
        for (var session : source.sessions()) {
            var measured = session.spots().stream().collect(Collectors.toMap(RawMeasurementSource.Spot::code, Function.identity()));
            int firstSpot = (session.courseCode().charAt(3) - 'A') * 2 + 1;
            for (int number = firstSpot; number <= firstSpot + 1; number++) {
                String code = "HS" + number;
                var spot = measured.get(code);
                records.add(new HealingMeasurementRecord((long) session.participantNo(), (long) number,
                        session.date(), session.courseCode(), session.baselineStress(),
                        spot == null ? null : spot.stress(), session.baselineEmotional(),
                        spot == null ? null : spot.emotional()));
            }
        }
        var result = new HealingImprovementAggregator().aggregate(records);

        try (var workbook = new XSSFWorkbook(Files.newInputStream(reference))) {
            assertReferenceRawFlags(workbook.getSheet("개별_체험기록"));
            assertOverall(workbook.getSheet("전체_스팟별"), result);
            assertP004Spots(workbook.getSheet("참여자별_스팟별"), result);
            assertP004Overall(workbook.getSheet("참여자별_전체"), result);
        }
        assertThat(result.memberSpots()).filteredOn(s -> s.memberId() == 7).hasSize(6);
        assertThat(result.participantOverall()).filteredOn(s -> s.memberId() == 7).singleElement()
                .satisfies(s -> {
                    assertThat(s.totalExperienceCount()).isEqualTo(6);
                    assertThat(s.stress()).isEqualTo(new HealingImprovementAggregator.MetricCounts(5, 4));
                    assertThat(s.emotional()).isEqualTo(new HealingImprovementAggregator.MetricCounts(6, 4));
                });
    }

    private Path createRawWorkbook() throws Exception {
        Path path = temporary.resolve("synthetic-measurements.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(RawMeasurementParser.SHEET);
            row(sheet, 0, RawMeasurementParser.HEADERS.toArray());
            for (int i = 0; i < VISITS.size(); i++) {
                var visit = VISITS.get(i);
                int first = visit.course() * 2 + 1;
                Object[] values = new Object[18];
                values[0] = "P%03d".formatted(visit.participant());
                values[1] = "2026-08-%02d".formatted(visit.day());
                values[2] = "HC-" + (char) ('A' + visit.course()) + " (" + (char) ('A' + visit.course()) + " 코스)";
                values[3] = "HS" + first + " " + HealingEffectExcelParser.NAMES.get(first - 1)
                        + " → HS" + (first + 1) + " " + HealingEffectExcelParser.NAMES.get(first);
                values[4] = 10; values[5] = 10;
                values[6 + (first - 1) * 2] = visit.firstStress();
                values[7 + (first - 1) * 2] = visit.firstEmotional();
                values[6 + first * 2] = visit.secondStress();
                values[7 + first * 2] = visit.secondEmotional();
                row(sheet, i + 1, values);
            }
            try (var output = Files.newOutputStream(path)) { book.write(output); }
        }
        return path;
    }

    private Path createReferenceWorkbook() throws Exception {
        Path path = temporary.resolve("synthetic-reference.xlsx");
        try (var book = new XSSFWorkbook()) {
            var flags = book.createSheet("개별_체험기록");
            flags.createRow(0);
            Object[][] expected = {
                    {10, 8, 1, 1, 10, 12, 1, 1}, {10, 10, 1, 0, 10, 9, 1, 0},
                    {10, null, 0, null, 10, 12, 1, 1}, {10, 8, 1, 1, 10, null, 0, null},
                    {10, 12, 1, 0, 10, 11, 1, 1}, {10, 7, 1, 1, 10, 10, 1, 0},
                    {10, 9, 1, 1, 10, 11, 1, 1}, {10, 8, 1, 1, 10, 12, 1, 1},
                    {10, 8, 1, 1, 10, 9, 1, 0}, {10, 10, 1, 0, 10, 11, 1, 1},
                    {10, 7, 1, 1, 10, 12, 1, 1}, {10, null, 0, null, 10, 9, 1, 0}
            };
            for (int i = 0; i < expected.length; i++) {
                Object[] cells = new Object[12];
                for (int j = 0; j < 4; j++) cells[4 + j] = expected[i][j];
                for (int j = 0; j < 4; j++) cells[8 + j] = expected[i][4 + j];
                row(flags, i + 1, cells);
            }
            var overall = book.createSheet("전체_스팟별");
            overall.createRow(0);
            row(overall, 1, "HS1", "Spot 1", 2, 2, 2, 2, 1.0, 2, 2, 1.0);
            row(overall, 2, "HS2", "Spot 2", 2, 2, 2, 1, 0.5, 2, 1, 0.5);
            row(overall, 3, "HS3", "Spot 3", 2, 2, 1, 1, 1.0, 2, 1, 0.5);
            row(overall, 4, "HS4", "Spot 4", 2, 2, 2, 1, 0.5, 1, 1, 1.0);
            row(overall, 5, "HS5", "Spot 5", 2, 2, 2, 1, 0.5, 2, 2, 1.0);
            row(overall, 6, "HS6", "Spot 6", 2, 2, 1, 1, 1.0, 2, 0, 0.0);
            var members = book.createSheet("참여자별_스팟별");
            members.createRow(0);
            row(members, 1, "P004", "HS1", "Spot 1", 1, 1, 1, 1.0, 1, 1, 1.0);
            row(members, 2, "P004", "HS2", "Spot 2", 1, 1, 0, 0.0, 1, 0, 0.0);
            row(members, 3, "P004", "HS3", "Spot 3", 1, 0, 0, null, 1, 1, 1.0);
            row(members, 4, "P004", "HS4", "Spot 4", 1, 1, 1, 1.0, 0, 0, null);
            row(members, 5, "P004", "HS5", "Spot 5", 1, 1, 0, 0.0, 1, 1, 1.0);
            row(members, 6, "P004", "HS6", "Spot 6", 1, 1, 1, 1.0, 1, 0, 0.0);
            var memberOverall = book.createSheet("참여자별_전체");
            memberOverall.createRow(0);
            row(memberOverall, 1, "P004", 6, 5, 3, 0.6, 5, 3, 0.6);
            try (var output = Files.newOutputStream(path)) { book.write(output); }
        }
        return path;
    }

    private void row(XSSFSheet sheet, int index, Object... values) {
        var row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
            else if (value instanceof String s) row.createCell(i).setCellValue(s);
        }
    }

    private void assertReferenceRawFlags(XSSFSheet sheet) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            BigDecimal baselineStress = nullableDecimal(row, 4);
            BigDecimal stress = nullableDecimal(row, 5);
            BigDecimal baselineEmotional = nullableDecimal(row, 8);
            BigDecimal emotional = nullableDecimal(row, 9);
            boolean stressValid = baselineStress != null && stress != null;
            boolean emotionalValid = baselineEmotional != null && emotional != null;
            assertThat(integer(row, 6)).isEqualTo(stressValid ? 1 : 0);
            assertThat(nullableInteger(row, 7)).isEqualTo(stressValid
                    ? (stress.compareTo(baselineStress) < 0 ? 1 : 0) : null);
            assertThat(integer(row, 10)).isEqualTo(emotionalValid ? 1 : 0);
            assertThat(nullableInteger(row, 11)).isEqualTo(emotionalValid
                    ? (emotional.compareTo(baselineEmotional) > 0 ? 1 : 0) : null);
        }
    }

    private void assertOverall(XSSFSheet sheet, HealingImprovementAggregator.Result result) {
        var actual = result.overallSpots().stream().collect(Collectors.toMap(r -> "HS" + r.spotId(), Function.identity()));
        assertThat(actual).hasSize(6);
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            var summary = actual.get(row.getCell(0).getStringCellValue());
            assertThat(summary.participantCount()).isEqualTo(integer(row, 2));
            assertThat(summary.totalExperienceCount()).isEqualTo(integer(row, 3));
            assertMetric(summary.stress(), row, 4, 5, 6);
            assertMetric(summary.emotional(), row, 7, 8, 9);
        }
    }

    private void assertP004Spots(XSSFSheet sheet, HealingImprovementAggregator.Result result) {
        var actual = result.memberSpots().stream().filter(r -> r.memberId() == 4)
                .collect(Collectors.toMap(r -> "HS" + r.spotId(), Function.identity()));
        assertThat(actual).hasSize(6);
        int matched = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            if (!"P004".equals(row.getCell(0).getStringCellValue())) continue;
            matched++;
            var summary = actual.get(row.getCell(1).getStringCellValue());
            assertThat(summary.totalExperienceCount()).isEqualTo(integer(row, 3));
            assertMetric(summary.stress(), row, 4, 5, 6);
            assertMetric(summary.emotional(), row, 7, 8, 9);
        }
        assertThat(matched).isEqualTo(6);
    }

    private void assertP004Overall(XSSFSheet sheet, HealingImprovementAggregator.Result result) {
        var summary = result.participantOverall().stream().filter(r -> r.memberId() == 4).findFirst().orElseThrow();
        var row = java.util.stream.IntStream.rangeClosed(1, sheet.getLastRowNum())
                .mapToObj(sheet::getRow).filter(r -> "P004".equals(r.getCell(0).getStringCellValue()))
                .findFirst().orElseThrow();
        assertThat(summary.totalExperienceCount()).isEqualTo(integer(row, 1));
        assertMetric(summary.stress(), row, 2, 3, 4);
        assertMetric(summary.emotional(), row, 5, 6, 7);
    }

    private void assertMetric(HealingImprovementAggregator.MetricCounts metric, XSSFRow row,
            int validColumn, int improvedColumn, int rateColumn) {
        assertThat(metric.validCount()).isEqualTo(integer(row, validColumn));
        assertThat(metric.improvedCount()).isEqualTo(integer(row, improvedColumn));
        var rate = ImprovementMetricView.of(metric.validCount(), metric.improvedCount()).improvementRate();
        if (metric.validCount() == 0) {
            assertThat(row.getCell(rateColumn)).isNull();
            assertThat(rate).isNull();
        } else {
            assertThat(rate).isCloseTo(decimal(row, rateColumn).multiply(BigDecimal.valueOf(100)),
                    within(new BigDecimal("0.000000000001")));
        }
    }

    private int integer(XSSFRow row, int column) { return decimal(row, column).intValueExact(); }
    private Integer nullableInteger(XSSFRow row, int column) {
        var value = nullableDecimal(row, column);
        return value == null ? null : value.intValueExact();
    }
    private BigDecimal decimal(XSSFRow row, int column) {
        return new BigDecimal(row.getCell(column).getRawValue());
    }
    private BigDecimal nullableDecimal(XSSFRow row, int column) {
        var cell = row.getCell(column);
        return cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK
                ? null : new BigDecimal(cell.getRawValue());
    }
}
