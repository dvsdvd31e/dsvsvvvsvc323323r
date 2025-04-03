package searchengine;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static LuceneMorphology russianMorph;
    private static LuceneMorphology englishMorph;

    static {
        try {
            russianMorph = new RussianLuceneMorphology();
            englishMorph = new EnglishLuceneMorphology();
        } catch (IOException e) {
            System.err.println("Ошибка при инициализации морфологического анализатора: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static HashMap<String, Integer> processText(String text, String language) {
        HashMap<String, Integer> lemmaCount = new HashMap<>();

        text = removeHtmlTags(text);

        String[] words = text.split("\\s+");

        LuceneMorphology luceneMorph = getMorphology(language);

        if (luceneMorph == null) {
            return lemmaCount;
        }

        Pattern pattern = Pattern.compile("[^a-zA-Zа-яА-ЯёЁ]");

        for (String word : words) {
            Matcher matcher = pattern.matcher(word);
            word = matcher.replaceAll("");

            word = word.toLowerCase();

            if (language.equals("en") && word.contains("'")) {
                continue;
            }

            try {
                List<String> wordBaseForms = luceneMorph.getMorphInfo(word);

                if (wordBaseForms.stream().noneMatch(info -> info.contains("СОЮЗ") ||
                        info.contains("МЕЖД") ||
                        info.contains("ПРЕДЛ") ||
                        info.contains("ЧАСТ") ||
                        info.contains("CONJ") ||
                        info.contains("PART"))) {

                    String lemma = luceneMorph.getNormalForms(word).get(0);

                    lemmaCount.put(lemma, lemmaCount.getOrDefault(lemma, 0) + 1);
                }
            } catch (Exception e) {
                System.err.println("Ошибка при обработке слова: " + word);
                e.printStackTrace();
            }
        }
        return lemmaCount;
    }

    public static String removeHtmlTags(String text) {
        return Jsoup.parse(text).text();
    }

    public static LuceneMorphology getMorphology(String language) {
        switch (language.toLowerCase()) {
            case "ru":
                return russianMorph;
            case "en":
                return englishMorph;
            default:
                throw new IllegalArgumentException("Неизвестный язык: " + language);
        }
    }

    public static void main(String[] args) {
        String htmlTextRu = "<html><body>Я люблю программировать! И <b>это</b> интересно. И это круто.</body></html>";
        System.out.println("Очищенный русский текст: " + removeHtmlTags(htmlTextRu));

        String textRu = "Я люблю программировать и создавать приложения, и это интересно.";
        HashMap<String, Integer> lemmaCountsRu = processText(textRu, "ru");
        System.out.println("Леммы и их количество для русского текста: " + lemmaCountsRu);

        String htmlTextEn = "<html><body>I love programming! And <b>this</b> is interesting. And it's cool.</body></html>";
        System.out.println("Очищенный английский текст: " + removeHtmlTags(htmlTextEn));

        String textEn = "I love programming and creating applications, and it's interesting.";
        HashMap<String, Integer> lemmaCountsEn = processText(textEn, "en");
        System.out.println("Леммы и их количество для английского текста: " + lemmaCountsEn);
    }
}
