package com.example.manage.healingeffect;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.*;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Reads only the supplied summaries. No evaluation, aggregation, rounding or source-file writes. */
public final class HealingEffectExcelParser {
    public static final String PARTICIPANTS = "02_참여자별_스팟요약";
    public static final String OVERALL = "03_전체_스팟요약";
    public static final List<String> CODES = List.of("HS1", "HS2", "HS3", "HS4", "HS5", "HS6");
    public static final List<String> NAMES = List.of("호스타 정원", "곶자왈원", "가든 위스퍼스",
            "콜로네이드 가든", "블로썸 가든", "극림원");
    public static final String STRESS_SESSIONS = "스트레스 유효회차(N)";
    public static final String STRESS_RATE = "스트레스 평균 감소율(%)";
    public static final String EMOTIONAL_SESSIONS = "정서안정성 유효회차(N)";
    public static final String EMOTIONAL_RATE = "정서안정성 평균 증가율(%)";
    public static final String STRESS_PARTICIPANTS = "스트레스 참여자수(N)";
    public static final String EMOTIONAL_PARTICIPANTS = "정서안정성 참여자수(N)";

    public HealingEffectSource parse(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw invalid("파일이 없거나 읽을 수 없습니다: " + path);
        // Hash exactly the same bytes that are parsed, avoiding a hash/parse race.
        byte[] bytes = Files.readAllBytes(path);
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        var errors = new ArrayList<String>();
        var overall = new ArrayList<HealingEffectSource.Overall>();
        var participants = new ArrayList<HealingEffectSource.Participant>();
        try (var input = new ByteArrayInputStream(bytes); var workbook = new XSSFWorkbook(input)) {
            var seenSpots = new HashSet<String>();
            read(workbook, OVERALL, List.of("스팟", "스팟명", STRESS_PARTICIPANTS, STRESS_SESSIONS,
                    STRESS_RATE, EMOTIONAL_PARTICIPANTS, EMOTIONAL_SESSIONS, EMOTIONAL_RATE), errors, (row, h) -> {
                String spot = spot(row, h);
                if (!seenSpots.add(spot))
                    throw invalid("중복 Spot: " + spot);
                overall.add(new HealingEffectSource.Overall(spot, count(row, h, STRESS_PARTICIPANTS),
                        count(row, h, STRESS_SESSIONS), number(row, h, STRESS_RATE),
                        count(row, h, EMOTIONAL_PARTICIPANTS), count(row, h, EMOTIONAL_SESSIONS),
                        number(row, h, EMOTIONAL_RATE)));
            });
            if (overall.size() != 6 || !seenSpots.equals(new HashSet<>(CODES)))
                errors.add(OVERALL + ": HS1~HS6 정확히 6행 필요, 유효 행=" + overall.size());
            var seenPairs = new HashSet<String>();
            read(workbook, PARTICIPANTS, List.of("참여자", "스팟", "스팟명", STRESS_SESSIONS, STRESS_RATE,
                    EMOTIONAL_SESSIONS, EMOTIONAL_RATE), errors, (row, h) -> {
                String participant = string(row, h, "참여자");
                if (!participant.matches("P[0-9]{3}"))
                    throw invalid("잘못된 Participant: " + participant);
                String spot = spot(row, h);
                if (!seenPairs.add(participant + ":" + spot))
                    throw invalid("중복 Participant + Spot: " + participant + " / " + spot);
                participants.add(new HealingEffectSource.Participant(participant,
                        Integer.parseInt(participant.substring(1)), spot,
                        count(row, h, STRESS_SESSIONS), number(row, h, STRESS_RATE),
                        count(row, h, EMOTIONAL_SESSIONS), number(row, h, EMOTIONAL_RATE)));
            });
            if (participants.isEmpty())
                errors.add(PARTICIPANTS + ": 유효 데이터가 없습니다.");
        } catch (EffectImportValidationException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw invalid("Workbook open/parse 실패: " + ex.getClass().getSimpleName());
        }
        if (!errors.isEmpty())
            throw new EffectImportValidationException(errors);
        return new HealingEffectSource(sha, path.getFileName().toString(), overall, participants);
    }

    private interface RowReader {
        void read(XSSFRow row, Map<String, Integer> headers);
    }

    private void read(XSSFWorkbook workbook, String name, List<String> required, List<String> errors, RowReader reader) {
        XSSFSheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            errors.add("Sheet missing: " + name);
            return;
        }
        XSSFRow header = sheet.getRow(0);
        if (header == null) {
            errors.add(name + ": Header missing");
            return;
        }
        var headers = new HashMap<String, Integer>();
        for (var cell : header) {
            if (cell.getCellType() == CellType.BLANK)
                continue;
            if (cell.getCellType() != CellType.STRING) {
                errors.add(name + ": Header must be text");
                return;
            }
            String value = cell.getStringCellValue();
            if (headers.putIfAbsent(value, cell.getColumnIndex()) != null) {
                errors.add(name + ": duplicate header " + value);
                return;
            }
        }
        var missing = required.stream().filter(s -> !headers.containsKey(s)).toList();
        if (!missing.isEmpty()) {
            errors.add(name + ": Header missing " + missing);
            return;
        }
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            XSSFRow row = sheet.getRow(i);
            if (row == null || java.util.stream.StreamSupport.stream(row.spliterator(), false)
                    .allMatch(c -> c.getCellType() == CellType.BLANK)) continue;
            try {
                reader.read(row, headers);
            } catch (IllegalArgumentException ex) {
                errors.add(name + " row " + (i + 1) + ": " + ex.getMessage());
            }
        }
    }

    private String spot(XSSFRow row, Map<String, Integer> headers) {
        String code = string(row, headers, "스팟");
        int index = CODES.indexOf(code);
        if (index < 0)
            throw invalid("잘못된 Spot: " + code);
        if (!NAMES.get(index).equals(string(row, headers, "스팟명")))
            throw invalid("Spot name 불일치: " + code);
        return code;
    }

    private XSSFCell cell(XSSFRow row, Map<String, Integer> headers, String header) {
        XSSFCell cell = row.getCell(headers.get(header));
        if (cell == null || cell.getCellType() == CellType.BLANK)
            throw invalid("필수 cell blank: " + header);
        return cell;
    }

    private String string(XSSFRow row, Map<String, Integer> headers, String header) {
        var cell = cell(row, headers, header);
        if (cell.getCellType() != CellType.STRING || cell.getStringCellValue().isBlank())
            throw invalid("필수 text cell 오류: " + header);
        return cell.getStringCellValue();
    }

    private BigDecimal number(XSSFRow row, Map<String, Integer> headers, String header) {
        var cell = cell(row, headers, header);
        // The supplied workbook has numeric literals. Reject formulas instead of evaluating or trusting stale caches.
        if (cell.getCellType() != CellType.NUMERIC)
            throw invalid("필수 numeric cell 오류: " + header);
        try {
            BigDecimal value = new BigDecimal(cell.getRawValue());
            BigDecimal stored = value.setScale(18, RoundingMode.UNNECESSARY);
            if (stored.precision() > 38)
                throw new ArithmeticException("precision");
            return value;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw invalid("DECIMAL(38,18)에 손실 없이 저장 불가: " + header + " " + cell.getRawValue());
        }
    }

    private int count(XSSFRow row, Map<String, Integer> headers, String header) {
        try {
            int count = number(row, headers, header).intValueExact();
            if (count < 0)
                throw new ArithmeticException("negative count");
            return count;
        } catch (ArithmeticException ex) {
            throw invalid("유효한 정수 count 필요: " + header);
        }
    }

    private static EffectImportValidationException invalid(String error) {
        return new EffectImportValidationException(List.of(error));
    }
}
