package com.opensmpsdeck.headless;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.opensmpsdeck.headless.PackageManifest.*;

final class Metadata {
    private static final String ID = "[a-z0-9][a-z0-9-]{0,63}";
    private static final String VERSION = "(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}";
    private static final Pattern LICENSE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]*\\+?|[()]");

    private static final class Spdx {
        static final Set<String> LICENSES;
        static final Set<String> EXCEPTIONS;
        static {
            var resource = Metadata.class.getResourceAsStream("/spdx-identifiers.txt");
            if (resource == null) throw new IllegalStateException("Missing SPDX identifier inventory");
            try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                reader.readLine();
                LICENSES = Set.of(reader.readLine().split(" "));
                EXCEPTIONS = Set.of(reader.readLine().split(" "));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read SPDX identifier inventory", error);
            }
        }
    }

    record Rights(@Nullable String composition, @Nullable String notes) {}
    record PackageInfo(String id, String name, String version, List<String> authors, @Nullable String license,
                       Rights rights) {}

    private Metadata() {}

    static PackageInfo make(String name, String id, String version, List<String> authors, String license) {
        if (id == null) {
            id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (id.isEmpty()) id = "song";
            if (id.length() > 64) id = id.substring(0, 64).replaceAll("-$", "");
        }
        var info = new PackageInfo(id, name, version == null ? "1.0.0" : version, authors, license, new Rights(null, null));
        validate(info);
        return info;
    }

    static void validate(PackageInfo info) {
        require(info.id().matches(ID), "Invalid package.id");
        require(info.version().matches(VERSION), "Invalid package.version, expected MAJOR.MINOR.PATCH");
        nonblank(info.name(), "package.name");
        require(info.authors().size() <= 32, "At most 32 package authors are allowed");
        for (String author : info.authors()) nonblank(author, "package author");
        if (info.license() != null) validateLicense(info.license());
    }

    static void validateLicense(String expression) {
        var parser = new LicenseParser(expression);
        require(parser.expression(0) && parser.atEnd(), "Invalid SPDX license expression");
    }

    private static final class LicenseParser {
        private final String text;
        private final java.util.regex.Matcher matcher;
        private int offset;

        LicenseParser(String text) {
            this.text = text;
            matcher = LICENSE_TOKEN.matcher(text);
        }

        boolean expression(int depth) {
            if (depth > 32 || !term(depth)) return false;
            while (take("AND") || take("OR")) if (!term(depth)) return false;
            return true;
        }

        boolean term(int depth) {
            if (take("(")) return expression(depth + 1) && take(")");
            String license = next();
            if (license == null || !(license.matches("(?:DocumentRef-[A-Za-z0-9.-]+:)?LicenseRef-[A-Za-z0-9.-]+")
                    || Spdx.LICENSES.contains(license.replaceFirst("[+]$", "").toLowerCase(Locale.ROOT))))
                return false;
            if (!take("WITH")) return true;
            String exception = next();
            return exception != null && (exception.matches("(?:DocumentRef-[A-Za-z0-9.-]+:)?AdditionRef-[A-Za-z0-9.-]+")
                    || Spdx.EXCEPTIONS.contains(exception.toLowerCase(Locale.ROOT)));
        }

        boolean take(String token) {
            int mark = offset;
            if (token.equals(next())) return true;
            offset = mark;
            return false;
        }

        String next() {
            skipSpace();
            if (!matcher.region(offset, text.length()).lookingAt()) return null;
            offset = matcher.end();
            return matcher.group();
        }

        boolean atEnd() {
            skipSpace();
            return offset == text.length();
        }

        private void skipSpace() {
            while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
        }
    }
}
