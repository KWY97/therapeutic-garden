package com.example.manage.personaleeg;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

/** Parser-shaped, invented measurements. No participant workbook is read or copied. */
final class SyntheticPersonalEegWorkbook {
    private static final Set<String> PREVIOUS_MISSING = Set.of(
            "3:2026-08-19:HS2", "3:2026-08-24:HS4",
            "4:2026-08-26:HS5", "5:2026-08-14:HS3");

    private SyntheticPersonalEegWorkbook() {}

    static Path create(Path directory, boolean current) throws Exception {
        Path file = directory.resolve(current ? "synthetic-current.xlsx" : "synthetic-previous.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("날짜별_결과");
            var header = sheet.createRow(0);
            for (int i = 0; i < 4; i++)
                header.createCell(i).setCellValue(new String[]{"참여자", "날짜", "코스", "코스명"}[i]);
            for (int spot = 1; spot <= 6; spot++) {
                int first = 4 + (spot - 1) * 4;
                String prefix = "HS" + spot + "_";
                header.createCell(first).setCellValue(prefix + "스트레스_전");
                header.createCell(first + 1).setCellValue(prefix + "스트레스_후");
                header.createCell(first + 2).setCellValue(prefix + "정서안정성_전");
                header.createCell(first + 3).setCellValue(prefix + "정서안정성_후");
            }
            int row = 1;
            for (int participant = 1; participant <= 11; participant++) {
                int sessions = participant == 11 ? 5 : 6;
                for (int visit = 0; visit < sessions; visit++) {
                    int course = visit % 3;
                    LocalDate date = LocalDate.of(2026, 7, 1).plusDays(participant * 7L + visit);
                    if (participant == 3 && visit == 0) date = LocalDate.of(2026, 8, 19);
                    if (participant == 3 && visit == 1) date = LocalDate.of(2026, 8, 24);
                    if (participant == 4 && visit == 2) date = LocalDate.of(2026, 8, 26);
                    if (participant == 5 && visit == 1) date = LocalDate.of(2026, 8, 14);
                    if (participant == 8 && visit == 1) date = LocalDate.of(2026, 1, 26);
                    addRow(sheet.createRow(row++), participant, date, course, current);
                }
            }
            if (current) {
                addRow(sheet.createRow(row++), 9, LocalDate.of(2026, 8, 21), 2, true);
                addRow(sheet.createRow(row), 10, LocalDate.of(2026, 8, 21), 2, true);
            }
            workbook.createSheet("EEG_날짜별_대역균형").createRow(0).createCell(0)
                    .setCellValue("synthetic unused band data");
            try (var output = Files.newOutputStream(file)) { workbook.write(output); }
        }
        return file;
    }

    private static void addRow(org.apache.poi.ss.usermodel.Row row, int participant, LocalDate date,
            int course, boolean current) {
        row.createCell(0).setCellValue("P%03d".formatted(participant));
        row.createCell(1).setCellValue(date.toString());
        row.createCell(2).setCellValue("HC-" + (char) ('A' + course));
        row.createCell(3).setCellValue((char) ('A' + course) + " 코스");
        for (int offset = 0; offset < 2; offset++) {
            int spot = course * 2 + offset + 1;
            int first = 4 + (spot - 1) * 4;
            double stress = participant == 3 && date.equals(LocalDate.of(2026, 8, 19))
                    ? 2.5 : 2.0 + participant / 10.0;
            double emotional = 1.0 + participant / 10.0;
            row.createCell(first).setCellValue(stress);
            row.createCell(first + 2).setCellValue(emotional);
            if (!current && PREVIOUS_MISSING.contains(participant + ":" + date + ":HS" + spot)) continue;
            row.createCell(first + 1).setCellValue(stress - 0.5);
            row.createCell(first + 3).setCellValue(emotional + 0.2);
        }
    }
}
