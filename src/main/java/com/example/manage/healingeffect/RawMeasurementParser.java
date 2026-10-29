package com.example.manage.healingeffect;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Read-only source parser. Each row is a dated Course measurement record, never a visit count. */
public final class RawMeasurementParser {
    public static final String SHEET = "01_원자료";
    public static final List<String> HEADERS;
    static {
        var headers = new ArrayList<>(List.of("참여자", "날짜", "코스", "체험 공간", "Baseline\n스트레스", "Baseline\n정서적 안정성"));
        for (int i = 1; i <= 6; i++) {
            headers.add("HS" + i + "\n스트레스");
            headers.add("HS" + i + "\n정서적 안정성");
        }
        HEADERS = List.copyOf(headers);
    }
    public RawMeasurementSource parse(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        var sessions = new ArrayList<RawMeasurementSource.Session>();
        var seen = new HashSet<String>();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(SHEET);
            if (sheet == null || sheet.getRow(0) == null) throw invalid("원자료 sheet/header 누락");
            if (sheet.getRow(0).getLastCellNum() != HEADERS.size()) throw invalid("원자료 column 수 불일치");
            for (int c = 0; c < HEADERS.size(); c++)
                if (!HEADERS.get(c).equals(text(sheet.getRow(0).getCell(c)))) throw invalid("원자료 header 불일치: " + HEADERS.get(c));
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row == null || java.util.stream.StreamSupport.stream(row.spliterator(), false).allMatch(c -> c.getCellType() == CellType.BLANK)) continue;
                try {
                    String participant = text(row.getCell(0));
                    if (!participant.matches("P[0-9]{3}") || participant.equals("P000")) throw invalid("Participant 형식 오류");
                    LocalDate date = date(row.getCell(1));
                    String course = text(row.getCell(2));
                    if (!List.of("HC-A (A 코스)", "HC-B (B 코스)", "HC-C (C 코스)").contains(course)) throw invalid("Course 형식 오류");
                    course = course.substring(0, 4);
                    if (!seen.add(participant + ":" + date)) throw invalid("동일 참가자+날짜 중복: session 의미 확인 필요");
                    String experience = text(row.getCell(3));
                    var measured = new ArrayList<RawMeasurementSource.Spot>();
                    for (int s = 0; s < 6; s++) {
                        var stress = number(row.getCell(6 + s * 2));
                        var emotional = number(row.getCell(7 + s * 2));
                        if (stress == null && emotional == null) continue;
                        if (!course.equals("HC-" + (char) ('A' + s / 2))) throw invalid("Spot/Course 불일치");
                        measured.add(new RawMeasurementSource.Spot("HS" + (s + 1), stress, emotional));
                    }
                    if (measured.isEmpty()) throw invalid("Spot 측정값 없음");
                    var route = new HashSet<String>();
                    for (String part : experience.split(" → ")) {
                        int index = HealingEffectExcelParser.CODES.indexOf(part.split(" ")[0]);
                        if (index < 0 || !part.equals(HealingEffectExcelParser.CODES.get(index) + " " + HealingEffectExcelParser.NAMES.get(index))
                                || !route.add(HealingEffectExcelParser.CODES.get(index))) throw invalid("체험 공간 형식 오류");
                    }
                    if (!route.equals(new HashSet<>(measured.stream().map(RawMeasurementSource.Spot::code).toList()))) throw invalid("체험 공간/측정 Spot 불일치");
                    sessions.add(new RawMeasurementSource.Session(i + 1, Integer.parseInt(participant.substring(1)), date,
                            course, experience, number(row.getCell(4)), number(row.getCell(5)), List.copyOf(measured)));
                } catch (IllegalArgumentException ex) {
                    throw invalid("원자료 row " + (i + 1) + ": " + ex.getMessage());
                }
            }
        }
        if (sessions.isEmpty()) throw invalid("원자료 기록 없음");
        return new RawMeasurementSource(sha, path.getFileName().toString(), List.copyOf(sessions));
    }
    private static LocalDate date(Cell cell) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            var value = cell.getLocalDateTimeCellValue();
            if (!value.toLocalTime().equals(LocalTime.MIDNIGHT)) throw invalid("시간이 있는 날짜: session 의미 확인 필요");
            return value.toLocalDate();
        }
        String value = text(cell);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("날짜 형식 오류");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException ex) {
            throw invalid("날짜 오류");
        }
    }
    private static String text(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.STRING || cell.getStringCellValue().isBlank()) throw invalid("필수 문자열 오류");
        return cell.getStringCellValue();
    }
    private static BigDecimal number(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() != CellType.NUMERIC) throw invalid("숫자 literal 필요 (formula/text 불가)");
        try {
            var value = new BigDecimal(((XSSFCell) cell).getRawValue());
            if (value.signum() < 0 || value.setScale(18, RoundingMode.UNNECESSARY).precision() > 38) throw new ArithmeticException();
            return value;
        } catch (ArithmeticException ex) {
            throw invalid("숫자 범위/정밀도 오류");
        }
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
