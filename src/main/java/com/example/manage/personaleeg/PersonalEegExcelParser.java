package com.example.manage.personaleeg;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Imports only the four reported values per dated HS row. EEG-only slots are out of scope. */
public final class PersonalEegExcelParser {
    private static final String DATE_SHEET = "날짜별_결과";
    private record Columns(String code, String stressPre, String stressPost,
                           String emotionalPre, String emotionalPost) {}
    private static final List<Columns> SPOTS = List.of(
            new Columns("HS1", "E", "F", "N", "O"),
            new Columns("HS2", "H", "I", "Q", "R"),
            new Columns("HS3", "W", "X", "AC", "AD"),
            new Columns("HS4", "Z", "AA", "AF", "AG"),
            new Columns("HS5", "AI", "AJ", "AO", "AP"),
            new Columns("HS6", "AL", "AM", "AR", "AS"));
    private static final Map<String, String> METRIC_HEADERS = Map.ofEntries(
            Map.entry("E", "HS1_스트레스_전"), Map.entry("F", "HS1_스트레스_후"),
            Map.entry("H", "HS2_스트레스_전"), Map.entry("I", "HS2_스트레스_후"),
            Map.entry("N", "HS1_정서안정성_전"), Map.entry("O", "HS1_정서안정성_후"),
            Map.entry("Q", "HS2_정서안정성_전"), Map.entry("R", "HS2_정서안정성_후"),
            Map.entry("W", "HS3_스트레스_전"), Map.entry("X", "HS3_스트레스_후"),
            Map.entry("Z", "HS4_스트레스_전"), Map.entry("AA", "HS4_스트레스_후"),
            Map.entry("AC", "HS3_정서안정성_전"), Map.entry("AD", "HS3_정서안정성_후"),
            Map.entry("AF", "HS4_정서안정성_전"), Map.entry("AG", "HS4_정서안정성_후"),
            Map.entry("AI", "HS5_스트레스_전"), Map.entry("AJ", "HS5_스트레스_후"),
            Map.entry("AL", "HS6_스트레스_전"), Map.entry("AM", "HS6_스트레스_후"),
            Map.entry("AO", "HS5_정서안정성_전"), Map.entry("AP", "HS5_정서안정성_후"),
            Map.entry("AR", "HS6_정서안정성_전"), Map.entry("AS", "HS6_정서안정성_후"));
    private static final Set<String> ALLOWED_MISSING = Set.of(
            "P003:2026-08-19:HS2", "P003:2026-08-24:HS4",
            "P004:2026-08-26:HS5", "P005:2026-08-14:HS3");
    private static final LocalDate P008_SOURCE_DATE = LocalDate.of(2026, 1, 26);
    private static final LocalDate P008_CONFIRMED_DATE = LocalDate.of(2026, 9, 4);

    public PersonalEegSource parse(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet dated = workbook.getSheet(DATE_SHEET);
            if (dated == null) throw invalid("날짜별 결과 시트 누락");
            requireHeaders(dated);
            var sessions = new ArrayList<PersonalEegSource.Session>();
            var participants = new HashSet<Integer>();
            var sourceKeys = new HashSet<String>();
            var displayKeys = new HashSet<String>();
            var missing = new HashSet<String>();
            int p008Sessions = 0;
            for (int i = 1; i <= dated.getLastRowNum(); i++) {
                Row row = dated.getRow(i);
                if (row == null || empty(row)) continue;
                String participant = requiredText(row, "A");
                if (!participant.matches("P[0-9]{3}") || participant.equals("P000"))
                    throw invalid("참가자 형식: row " + (i + 1));
                int number = Integer.parseInt(participant.substring(1));
                LocalDate sourceDate = date(row, "B");
                String course = requiredText(row, "C");
                if (!List.of("HC-A", "HC-B", "HC-C").contains(course))
                    throw invalid("코스 형식: row " + (i + 1));
                if (!((char) ('A' + List.of("HC-A", "HC-B", "HC-C").indexOf(course)) + " 코스")
                        .equals(requiredText(row, "D")))
                    throw invalid("코스명 불일치: row " + (i + 1));
                LocalDate confirmedDate = sourceDate;
                if (number == 8 && course.equals("HC-B")
                        && (sourceDate.equals(P008_SOURCE_DATE) || sourceDate.equals(P008_CONFIRMED_DATE))) {
                    p008Sessions++;
                    confirmedDate = P008_CONFIRMED_DATE;
                }
                int first = (course.charAt(3) - 'A') * 2;
                var spots = new ArrayList<PersonalEegSource.Spot>();
                for (int s = 0; s < SPOTS.size(); s++) {
                    Columns c = SPOTS.get(s);
                    if (s < first || s >= first + 2) {
                        if (anyPresent(row, c)) throw invalid("다른 코스의 HS 값: row " + (i + 1));
                        continue;
                    }
                    BigDecimal baselineStress = requiredPositive(row, c.stressPre());
                    BigDecimal baselineEmotional = requiredPositive(row, c.emotionalPre());
                    BigDecimal stressPost = optionalPositive(row, c.stressPost());
                    BigDecimal emotionalPost = optionalPositive(row, c.emotionalPost());
                    if ((stressPost == null) != (emotionalPost == null))
                        throw invalid("Post 지표 쌍 누락: row " + (i + 1));
                    if (stressPost == null) {
                        String missingKey = participant + ":" + sourceDate + ":" + c.code();
                        if (!ALLOWED_MISSING.contains(missingKey))
                            throw invalid("예상 밖 Post 결측: " + missingKey);
                        missing.add(missingKey);
                    }
                    spots.add(new PersonalEegSource.Spot(c.code(), baselineStress, stressPost,
                            baselineEmotional, emotionalPost));
                }
                if (spots.get(0).baselineStress().compareTo(spots.get(1).baselineStress()) != 0
                        || spots.get(0).baselineEmotional().compareTo(spots.get(1).baselineEmotional()) != 0)
                    throw invalid("코스 내 Baseline 불일치: row " + (i + 1));
                if (!sourceKeys.add(key(participant, sourceDate, course)))
                    throw invalid("동일 참가자/원본 날짜/코스 중복: row " + (i + 1));
                if (!displayKeys.add(key(participant, confirmedDate, course)))
                    throw invalid("정정 후 참가자/날짜/코스 충돌: row " + (i + 1));
                sessions.add(new PersonalEegSource.Session(i + 1, number, sourceDate,
                        confirmedDate, course, spots));
                participants.add(number);
            }
            int resultCount = sessions.stream().mapToInt(s -> s.spots().size()).sum();
            if (sessions.size() != 65 || participants.size() != 11 || resultCount != 130
                    || p008Sessions != 1 || !ALLOWED_MISSING.containsAll(missing)
                    || resultCount - missing.size() != sessions.stream().flatMap(s -> s.spots().stream())
                            .filter(s -> s.stressPost() != null).count())
                throw invalid("날짜별 보고서 건수/매핑 불일치");
            return new PersonalEegSource(sha256(bytes), path.getFileName().toString(), sessions);
        }
    }

    private static void requireHeaders(Sheet sheet) {
        Row row = sheet.getRow(0);
        if (row == null) throw invalid("날짜별 header 누락");
        Map<String, String> headers = new HashMap<>(METRIC_HEADERS);
        headers.put("A", "참여자"); headers.put("B", "날짜");
        headers.put("C", "코스"); headers.put("D", "코스명");
        for (var header : headers.entrySet())
            if (!header.getValue().equals(requiredText(row, header.getKey())))
                throw invalid("날짜별 header 불일치: " + header.getKey());
    }
    private static boolean anyPresent(Row row, Columns c) {
        return Arrays.stream(new String[]{c.stressPre(), c.stressPost(), c.emotionalPre(), c.emotionalPost()})
                .anyMatch(col -> row.getCell(CellReference.convertColStringToIndex(col)) != null
                        && row.getCell(CellReference.convertColStringToIndex(col)).getCellType() != CellType.BLANK);
    }
    private static boolean empty(Row row) {
        for (Cell cell : row) if (cell.getCellType() != CellType.BLANK) return false;
        return true;
    }
    private static String requiredText(Row row, String col) {
        Cell cell = row.getCell(CellReference.convertColStringToIndex(col));
        if (cell == null || cell.getCellType() != CellType.STRING || cell.getStringCellValue().isBlank())
            throw invalid("문자열 literal 누락: row " + (row.getRowNum() + 1) + ", col " + col);
        return cell.getStringCellValue();
    }
    private static LocalDate date(Row row, String col) {
        try { return LocalDate.parse(requiredText(row, col)); }
        catch (DateTimeException ex) { throw invalid("날짜 형식 오류: row " + (row.getRowNum() + 1)); }
    }
    private static BigDecimal requiredPositive(Row row, String col) {
        BigDecimal value = optionalPositive(row, col);
        if (value == null) throw invalid("Baseline 누락: row " + (row.getRowNum() + 1) + ", " + col);
        return value;
    }
    private static BigDecimal optionalPositive(Row row, String col) {
        Cell cell = row.getCell(CellReference.convertColStringToIndex(col));
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() != CellType.NUMERIC || !(cell instanceof XSSFCell xcell))
            throw invalid("숫자 literal 필요: row " + (row.getRowNum() + 1) + ", " + col);
        try {
            BigDecimal value = new BigDecimal(xcell.getRawValue());
            if (value.signum() <= 0 || value.scale() > 18 || value.precision() > 38)
                throw new ArithmeticException();
            return value;
        } catch (ArithmeticException ex) {
            throw invalid("양수/정밀도 오류: row " + (row.getRowNum() + 1) + ", " + col);
        }
    }
    private static String key(String participant, LocalDate date, String course) {
        return participant + ":" + date + ":" + course;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
