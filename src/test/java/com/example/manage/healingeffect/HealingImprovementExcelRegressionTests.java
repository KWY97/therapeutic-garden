package com.example.manage.healingeffect;

import com.example.manage.domain.HealingMeasurementRecord;
import com.example.manage.dto.ImprovementMetricView;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class HealingImprovementExcelRegressionTests {
    private static final Path RAW = Path.of("../private-data/healing_spot_stress_emotional_summary.xlsx");
    private static final Path REFERENCE = Path.of("../private-data/healing_spot_improvement_counts.xlsx");

    @Test void rawAggregationMatchesAllReferenceSpotsAndP004() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RAW) && Files.isRegularFile(REFERENCE),
                "External private-data workbooks are not distributed with the repository");

        var source = new RawMeasurementParser().parse(RAW);
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

        try (var workbook = new XSSFWorkbook(Files.newInputStream(REFERENCE))) {
            assertReferenceRawFlags(workbook.getSheet("개별_체험기록"));
            assertOverall(workbook.getSheet("전체_스팟별"), result);
            assertP004Spots(workbook.getSheet("참여자별_스팟별"), result);
            assertP004Overall(workbook.getSheet("참여자별_전체"), result);
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
        assertThat(ImprovementMetricView.of(metric.validCount(), metric.improvedCount()).improvementRate())
                .isCloseTo(decimal(row, rateColumn).multiply(BigDecimal.valueOf(100)), within(new BigDecimal("0.000000000001")));
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
