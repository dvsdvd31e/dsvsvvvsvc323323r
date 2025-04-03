package searchengine;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;

import java.io.IOException;
import java.util.*;


public class Lemmatizer {
    private static final Set<String> EXCLUDED_PARTS_OF_SPEECH = new HashSet<>(Arrays.asList(
            "PREP", "CONJ", "PRCL", "INTJ"
    ));

    private LuceneMorphology luceneMorphology;

    public Lemmatizer(String language) {
        try {
            if ("ru".equalsIgnoreCase(language)) {
                luceneMorphology = new RussianLuceneMorphology();
            } else if ("en".equalsIgnoreCase(language)) {
                luceneMorphology = new EnglishLuceneMorphology();
            } else {
                throw new IllegalArgumentException("Unsupported language: " + language);
            }
        } catch (IOException e) {
            System.err.println("Error initializing lemmatizer: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Map<String, Integer> getLemmas(String text) {
        text = text.replaceAll("[^a-zA-Zа-яА-ЯёЁ]", " ");
        String[] words = text.split("\\s+");
        Map<String, Integer> lemmaCount = new HashMap<>();

        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }

            word = word.toLowerCase();

            List<String> lemmas = luceneMorphology.getNormalForms(word);
            if (!lemmas.isEmpty()) {
                String lemma = lemmas.get(0);

                List<String> grammemes = luceneMorphology.getMorphInfo(word);
                for (String grammeme : grammemes) {
                    if (!isExcludedPartOfSpeech(grammeme)) {
                        lemmaCount.put(lemma, lemmaCount.getOrDefault(lemma, 0) + 1);
                    }
                }
            }
        }

        return lemmaCount;
    }

    private boolean isExcludedPartOfSpeech(String grammeme) {
        for (String partOfSpeech : EXCLUDED_PARTS_OF_SPEECH) {
            if (grammeme.contains(partOfSpeech)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        String russianText = "Повторное появление леопарда в Осетии позволяет предположить, что леопард постоянно обитает в некоторых районах Северного Кавказа.";
        String englishText = "The repeated appearance of the leopard in Ossetia suggests that the leopard constantly lives in some areas of the North Caucasus.";

        Lemmatizer ruLemmatizer = new Lemmatizer("ru");
        Map<String, Integer> ruResult = ruLemmatizer.getLemmas(russianText);
        System.out.println("Russian Lemmas:");
        for (Map.Entry<String, Integer> entry : ruResult.entrySet()) {
            System.out.println(entry.getKey() + " — " + entry.getValue());
        }

        Lemmatizer enLemmatizer = new Lemmatizer("en");
        Map<String, Integer> enResult = enLemmatizer.getLemmas(englishText);
        System.out.println("\nEnglish Lemmas:");
        for (Map.Entry<String, Integer> entry : enResult.entrySet()) {
            System.out.println(entry.getKey() + " — " + entry.getValue());
        }
    }
}
