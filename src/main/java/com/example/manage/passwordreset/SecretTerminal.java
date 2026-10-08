package com.example.manage.passwordreset;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

interface SecretTerminal extends AutoCloseable {
    char[] readSecret(String prompt);
    String readLine(String prompt);
    void printSecretLine(String text);

    @Override
    default void close() {}

    static SecretTerminal system() {
        Console console = System.console();
        if (console != null) return new ConsoleTerminal(console);
        return TtyTerminal.open();
    }
}

final class ConsoleTerminal implements SecretTerminal {
    private final Console console;

    ConsoleTerminal(Console console) {
        this.console = console;
    }

    @Override public char[] readSecret(String prompt) {
        char[] value = console.readPassword("%s", prompt);
        if (value == null) throw new IllegalStateException("터미널 입력이 종료되었습니다.");
        return SecretInput.normalizeBracketedPaste(value);
    }

    @Override public String readLine(String prompt) {
        String value = console.readLine("%s", prompt);
        if (value == null) throw new IllegalStateException("터미널 입력이 종료되었습니다.");
        return value;
    }

    @Override public void printSecretLine(String text) {
        console.writer().println(text);
        console.writer().flush();
    }
}

/** Uses the controlling terminal when Gradle's JavaExec makes System.console() unavailable. */
final class TtyTerminal implements SecretTerminal {
    private static final Path TTY = Path.of("/dev/tty");
    private final BufferedReader reader;
    private final PrintWriter writer;

    private TtyTerminal(BufferedReader reader, PrintWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    static SecretTerminal open() {
        if (!Files.isReadable(TTY) || !Files.isWritable(TTY)) {
            throw new IllegalStateException("대화형 로컬 터미널이 필요합니다. 입력 리다이렉션은 허용되지 않습니다.");
        }
        try {
            var reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(TTY, StandardOpenOption.READ), StandardCharsets.UTF_8));
            OutputStream output = Files.newOutputStream(TTY, StandardOpenOption.WRITE);
            return new TtyTerminal(reader, new PrintWriter(output, true, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("대화형 로컬 터미널을 열 수 없습니다.");
        }
    }

    @Override public char[] readSecret(String prompt) {
        writer.print(prompt);
        writer.flush();
        setEcho(false);
        try {
            String value = reader.readLine();
            writer.println();
            if (value == null) throw new IllegalStateException("터미널 입력이 종료되었습니다.");
            return SecretInput.normalizeBracketedPaste(value.toCharArray());
        } catch (IOException ex) {
            throw new IllegalStateException("터미널 입력에 실패했습니다.");
        } finally {
            setEcho(true);
        }
    }

    @Override public String readLine(String prompt) {
        writer.print(prompt);
        writer.flush();
        try {
            String value = reader.readLine();
            if (value == null) throw new IllegalStateException("터미널 입력이 종료되었습니다.");
            return value;
        } catch (IOException ex) {
            throw new IllegalStateException("터미널 입력에 실패했습니다.");
        }
    }

    @Override public void printSecretLine(String text) {
        writer.println(text);
    }

    private void setEcho(boolean enabled) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c",
                    enabled ? "stty echo < /dev/tty" : "stty -echo < /dev/tty")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor() != 0) throw new IllegalStateException("터미널 echo 설정에 실패했습니다.");
        } catch (IOException ex) {
            throw new IllegalStateException("터미널 echo 설정에 실패했습니다.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("터미널 입력이 중단되었습니다.");
        }
    }

    @Override public void close() {
        try {
            reader.close();
        } catch (IOException ignored) {
            // Best effort; no secret is logged.
        }
        writer.close();
    }
}

/** Normalizes only the standard, complete xterm bracketed-paste envelope. */
final class SecretInput {
    private static final char[] PASTE_BEGIN = "\u001B[200~".toCharArray();
    private static final char[] PASTE_END = "\u001B[201~".toCharArray();

    private SecretInput() {}

    static char[] normalizeBracketedPaste(char[] input) {
        boolean beginsPaste = startsWith(input, PASTE_BEGIN);
        boolean endsPaste = endsWith(input, PASTE_END);
        if (!beginsPaste && !endsPaste) return input;

        if (!beginsPaste || !endsPaste || input.length < PASTE_BEGIN.length + PASTE_END.length) {
            Arrays.fill(input, '\0');
            throw new IllegalArgumentException("완전하지 않은 bracketed paste 입력을 거부했습니다. 다시 붙여넣어 주세요.");
        }

        char[] normalized = Arrays.copyOfRange(input, PASTE_BEGIN.length, input.length - PASTE_END.length);
        Arrays.fill(input, '\0');
        return normalized;
    }

    private static boolean startsWith(char[] input, char[] prefix) {
        if (input.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (input[index] != prefix[index]) return false;
        }
        return true;
    }

    private static boolean endsWith(char[] input, char[] suffix) {
        if (input.length < suffix.length) return false;
        int offset = input.length - suffix.length;
        for (int index = 0; index < suffix.length; index++) {
            if (input[offset + index] != suffix[index]) return false;
        }
        return true;
    }
}
