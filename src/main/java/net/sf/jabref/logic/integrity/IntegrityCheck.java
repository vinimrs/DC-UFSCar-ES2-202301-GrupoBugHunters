package net.sf.jabref.logic.integrity;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.sf.jabref.BibDatabaseContext;
import net.sf.jabref.Globals;
import net.sf.jabref.bibtex.FieldProperties;
import net.sf.jabref.bibtex.InternalBibtexFields;
import net.sf.jabref.logic.l10n.Localization;
import net.sf.jabref.logic.util.io.FileUtil;
import net.sf.jabref.model.entry.BibEntry;
import net.sf.jabref.model.entry.FileField;
import net.sf.jabref.model.entry.ParsedFileField;


public class IntegrityCheck {

    private final BibDatabaseContext bibDatabaseContext;

    public IntegrityCheck(BibDatabaseContext bibDatabaseContext) {
        this.bibDatabaseContext = Objects.requireNonNull(bibDatabaseContext);
    }

    public IntegrityCheck() {
        this.bibDatabaseContext = null;
    }

    public List<IntegrityMessage> checkBibtexDatabase() {
        List<IntegrityMessage> result = new ArrayList<>();

        for (BibEntry entry : bibDatabaseContext.getDatabase().getEntries()) {
            result.addAll(checkBibtexEntry(entry));
        }

        return result;
    }

    public List<IntegrityMessage> checkBibtexEntry(BibEntry entry) {
        List<IntegrityMessage> result = new ArrayList<>();

        if (entry == null) {
            return result;
        }

        result.addAll(new AuthorNameChecker().check(entry));

        if (bibDatabaseContext != null) {
            result.addAll(new FileChecker(bibDatabaseContext).check(entry));

            if (!bibDatabaseContext.isBiblatexMode()) {
                result.addAll(new TitleChecker().check(entry));
            }
        }


        result.addAll(new BracketChecker("title").check(entry));
        result.addAll(new YearChecker().check(entry));
        result.addAll(new PagesChecker().check(entry));
        result.addAll(new UrlChecker().check(entry));
        // Mais uma tarefa na bateria de checagem
        result.addAll(new BibTexKeyChecker(this.bibDatabaseContext).check(entry));

        result.addAll(new TypeChecker().check(entry));
        result.addAll(new AbbreviationChecker("journal").check(entry));
        result.addAll(new AbbreviationChecker("booktitle").check(entry));
        result.addAll(new BibStringChecker().check(entry));
        result.addAll(new HTMLCharacterChecker().check(entry));
        result.addAll(new ISSNChecker().check(entry));

        return result;
    }


    @FunctionalInterface
    public interface Checker {
        List<IntegrityMessage> check(BibEntry entry);
    }

    private static class TypeChecker implements Checker {

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional("pages");
            if (!value.isPresent()) {
                return Collections.emptyList();
            }

            if ("proceedings".equalsIgnoreCase(entry.getType())) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("wrong entry type as proceedings has page numbers"), entry, "pages"));
            }

            return Collections.emptyList();
        }
    }

    private static class AbbreviationChecker implements Checker {

        private final String field;

        private AbbreviationChecker(String field) {
            this.field = field;
        }

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional(field);
            if (!value.isPresent()) {
                return Collections.emptyList();
            }

            if (value.get().contains(".")) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("abbreviation detected"), entry, field));
            }

            return Collections.emptyList();
        }
    }

    private static class FileChecker implements Checker {

        private final BibDatabaseContext context;

        private FileChecker(BibDatabaseContext context) {
            this.context = context;
        }

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional(Globals.FILE_FIELD);
            if (!value.isPresent()) {
                return Collections.emptyList();
            }

            List<ParsedFileField> parsedFileFields = FileField.parse(value.get()).stream()
                    .filter(p -> !(p.getLink().startsWith("http://") || p.getLink().startsWith("https://")))
                    .collect(Collectors.toList());

            for (ParsedFileField p : parsedFileFields) {
                Optional<File> file = FileUtil.expandFilename(context, p.getLink());
                if ((!file.isPresent()) || !file.get().exists()) {
                    return Collections.singletonList(
                            new IntegrityMessage(Localization.lang("link should refer to a correct file path"), entry,
                                    Globals.FILE_FIELD));
                }
            }

            return Collections.emptyList();
        }
    }

    private static class UrlChecker implements Checker {

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional("url");
            if (!value.isPresent()) {
                return Collections.emptyList();
            }

            if (!value.get().contains("://")) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("should contain a protocol") + ": http[s]://, file://, ftp://, ...", entry, "url"));
            }

            return Collections.emptyList();
        }
    }

    private static class AuthorNameChecker implements Checker {

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            List<IntegrityMessage> result = new ArrayList<>();
            for (String field : entry.getFieldNames()) {
                if (InternalBibtexFields.getFieldExtras(field).contains(FieldProperties.PERSON_NAMES)) {
                    Optional<String> value = entry.getFieldOptional(field);
                    if (!value.isPresent()) {
                        return Collections.emptyList();
                    }

                    String valueTrimmedAndLowerCase = value.get().trim().toLowerCase();
                    if (valueTrimmedAndLowerCase.startsWith("and ") || valueTrimmedAndLowerCase.startsWith(",")) {
                        result.add(new IntegrityMessage(Localization.lang("should start with a name"), entry, field));
                    } else if (valueTrimmedAndLowerCase.endsWith(" and") || valueTrimmedAndLowerCase.endsWith(",")) {
                        result.add(new IntegrityMessage(Localization.lang("should end with a name"), entry, field));
                    }
                }
            }
            return result;
        }
    }


    private static class BracketChecker implements Checker {

        private final String field;

        private BracketChecker(String field) {
            this.field = field;
        }

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional(field);
            if (!value.isPresent()) {
                return Collections.emptyList();
            }

            // metaphor: integer-based stack (push + / pop -)
            int counter = 0;
            for (char a : value.get().trim().toCharArray()) {
                if (a == '{') {
                    counter++;
                } else if (a == '}') {
                    if (counter == 0) {
                        return Collections.singletonList(new IntegrityMessage(Localization.lang("unexpected closing curly bracket"), entry, field));
                    } else {
                        counter--;
                    }
                }
            }

            if (counter > 0) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("unexpected opening curly bracket"), entry, field));
            }

            return Collections.emptyList();
        }

    }

    private static class TitleChecker implements Checker {

        private static final Pattern INSIDE_CURLY_BRAKETS = Pattern.compile("\\{[^}\\{]*\\}");
        private static final Predicate<String> HAS_CAPITAL_LETTERS = Pattern.compile("[\\p{Lu}\\p{Lt}]").asPredicate();

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional("title");
            if (!value.isPresent()) {
                return Collections.emptyList();
            }


            /*
             * Algorithm:
             * - remove trailing whitespaces
             * - ignore first letter as this can always be written in caps
             * - remove everything that is in brackets
             * - check if at least one capital letter is in the title
             */
            String valueTrimmed = value.get().trim();
            String valueIgnoringFirstLetter = valueTrimmed.startsWith("{") ? valueTrimmed : valueTrimmed.substring(1);
            String valueOnlySpacesWithinCurlyBraces = valueIgnoringFirstLetter;
            while (true) {
                Matcher matcher = INSIDE_CURLY_BRAKETS.matcher(valueOnlySpacesWithinCurlyBraces);
                if (!matcher.find()) {
                    break;
                }
                valueOnlySpacesWithinCurlyBraces = matcher.replaceAll("");
            }

            boolean hasCapitalLettersThatBibtexWillConvertToSmallerOnes = HAS_CAPITAL_LETTERS.test(valueOnlySpacesWithinCurlyBraces);

            if (hasCapitalLettersThatBibtexWillConvertToSmallerOnes) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("large capitals are not masked using curly brackets {}"), entry, "title"));
            }

            return Collections.emptyList();
        }
    }

    /**
     * Classe utilitária para checagem do campo Ano
     *
     * - Year: deve ser inserido somente um ano válido
     *          (de acordo com o calendário da linguagem Java)
     */
    private static class YearChecker implements Checker {

        private static final Predicate<String> CONTAINS_FOUR_DIGIT = Pattern.compile("([^0-9]|^)[0-9]{4}([^0-9]|$)").asPredicate();

        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            String value = entry.getField("year");

            // Não permitimos anos vazios
            if (value == null) {
                return Collections.singletonList(
                        new IntegrityMessage(Localization.lang("should contain some value"), entry, "year"));

            }

            // Não permitimos anos diferentes de 4 dígitos
            if (!CONTAINS_FOUR_DIGIT.test(value.trim())) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("should contain a four digit number"), entry, "year"));
            }

            // Início do novo código de verificação do ano
            int date = Integer.parseInt(value.trim());

            // 1- O ano de uma entrada Bibtex não pode estar no futuro
            if (date > LocalDate.now().getYear()) {
                return Collections.singletonList(
                        new IntegrityMessage(Localization.lang("shouldn't be on the future"), entry, "year"));
            }

            // 2- O ano não pode estar "muito" no passado
            if (date < 1452) {
                return Collections.singletonList(
                        new IntegrityMessage(Localization.lang("it should not be before the creation of the press"),
                                entry, "year"));
            }

            return Collections.emptyList();
        }
    }

    /**
     * Classe utilitária para checagem do campo bibtexkey
     *
     * - Bibtexkey: definido pelo usuário ou automaticamente,
     *              deve ter no mínimo 2 caracteres,
     *              sendo o primeiro uma letra maiúscula ou minúscula.
     */
    private static class BibTexKeyChecker implements Checker {

        // Regex para verificação da validade da key
        private static final Predicate<String> STARTS_WITH_LETTER = Pattern.compile("([a-zA-Z])([^\\s])").asPredicate();
        private final BibDatabaseContext bibDatabaseContext;


        public BibTexKeyChecker(BibDatabaseContext bibDatabaseContext) {
            // TODO Auto-generated constructor stub
            this.bibDatabaseContext = bibDatabaseContext;
        }


        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            String value = entry.getCiteKey();

            // Verifica a duplicacao na base
            // > 1 pois a key já vai estar mapeada automaticamente no Map<> allKeys.
            boolean isDuplicate = bibDatabaseContext.getDatabase().getNumberOfKeyOccurrences(value) > 1;

            // Não permitimos keys duplicadas
            if (isDuplicate) {
                return Collections.singletonList(
                        new IntegrityMessage(Localization.lang("should be unique in database"), entry, "bibtexkey"));
            }

            // Não permitimos keys vazias
            if (value == null) {
                return Collections.singletonList(
                        new IntegrityMessage(Localization.lang("should contains some value"), entry, "bibtexkey"));
            }

            // Não permitimos keys com menos de 2 caracteres
            if (value.length() < 2) {
                return Collections.singletonList(new IntegrityMessage(
                        Localization.lang("should be at least two characters long"), entry, "bibtexkey"));
            }

            // Não permitimos keys que não iniciam com uma letra
            if (!STARTS_WITH_LETTER.test(value)) {
                return Collections.singletonList(
                        new IntegrityMessage(Localization.lang("should start with letter"), entry, "bibtexkey"));
            }

            return Collections.emptyList();
        }
    }

    /**
     * From BibTex manual:
     * One or more page numbers or range of numbers, such as 42--111 or 7,41,73--97 or 43+
     * (the '+' in this last example indicates pages following that don't form a simple range).
     * To make it easier to maintain Scribe-compatible databases, the standard styles convert
     * a single dash (as in 7-33) to the double dash used in TEX to denote number ranges (as in 7--33).
     */
    private static class PagesChecker implements Checker {

        private static final String PAGES_EXP = ""
                + "\\A"                       // begin String
                + "\\d+"                      // number
                + "(?:"                       // non-capture group
                + "\\+|\\-{2}\\d+"            // + or --number (range)
                + ")?"                        // optional group
                + "(?:"                       // non-capture group
                + ","                         // comma
                + "\\d+(?:\\+|\\-{2}\\d+)?"   // repeat former pattern
                + ")*"                        // repeat group 0,*
                + "\\z";                      // end String

        private static final Predicate<String> VALID_PAGE_NUMBER = Pattern.compile(PAGES_EXP).asPredicate();

        /**
         * Checks, if the page numbers String conforms to the BibTex manual
         */
        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            Optional<String> value = entry.getFieldOptional("pages");
            if (!value.isPresent()) {
                return Collections.emptyList();
            }

            if (!VALID_PAGE_NUMBER.test(value.get().trim())) {
                return Collections.singletonList(new IntegrityMessage(Localization.lang("should contain a valid page number range"), entry, "pages"));
            }

            return Collections.emptyList();
        }
    }

    private static class BibStringChecker implements Checker {

        // Detect # if it doesn't have a \ in front of it or if it starts the string
        private static final Pattern UNESCAPED_HASH = Pattern.compile("(?<!\\\\)#|^#");


        /**
         * Checks, if there is an even number of unescaped #
         */
        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            List<IntegrityMessage> results = new ArrayList<>();
            for (Map.Entry<String, String> field : entry.getFieldMap().entrySet()) {
                Matcher hashMatcher = UNESCAPED_HASH.matcher(field.getValue());
                int hashCount = 0;
                while (hashMatcher.find()) {
                    hashCount++;
                }
                if ((hashCount % 2) == 1) {
                    results.add(
                            new IntegrityMessage(Localization.lang("odd number of unescaped '#'"), entry,
                                    field.getKey()));
                }
            }
            return results;
        }
    }

    private static class HTMLCharacterChecker implements Checker {

        // Detect any HTML encoded character,
        private static final Pattern HTML_CHARACTER_PATTERN = Pattern.compile("&[#\\p{Alnum}]+;");


        /**
         * Checks, if there are any HTML encoded characters in the fields
         */
        @Override
        public List<IntegrityMessage> check(BibEntry entry) {
            List<IntegrityMessage> results = new ArrayList<>();
            for (Map.Entry<String, String> field : entry.getFieldMap().entrySet()) {
                Matcher characterMatcher = HTML_CHARACTER_PATTERN.matcher(field.getValue());
                if (characterMatcher.find()) {
                    results.add(new IntegrityMessage(Localization.lang("HTML encoded character found"), entry,
                            field.getKey()));
                }
            }
            return results;
        }
    }

}
