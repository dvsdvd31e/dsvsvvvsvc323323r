package searchengine.utils;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class LemmaProcessor {
    private static final Logger logger = LoggerFactory.getLogger(LemmaProcessor.class);
    private final LuceneMorphology russianMorphology;
    private final LuceneMorphology englishMorphology;
    private static final Set<String> PARTICLES = Set.of("ПРЕДЛ", "СОЮЗ", "МЕЖД", "ЧАСТ");

    public LemmaProcessor() throws Exception {
        this.russianMorphology = new RussianLuceneMorphology();
        this.englishMorphology = new EnglishLuceneMorphology();
    }

    public List<String> extractLemmas(String text) {
        List<String> words = splitIntoWords(text.toLowerCase());
        return words.stream()
                .map(this::lemmatizeWord)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String lemmatizeWord(String word) {
        try {
            if (word.matches(".*[а-яА-Я]+.*")) {
                return processLemmas(russianMorphology, word);
            } else if (word.matches(".*[a-zA-Z]+.*")) {
                return processLemmas(englishMorphology, word);
            }
        } catch (Exception e) {
            System.out.println("Ошибка обработки слова: " + word);
        }
        return null;
    }

    private String processLemmas(LuceneMorphology morphology, String word) {
        List<String> normalForms = morphology.getNormalForms(word);
        List<String> wordInfo = morphology.getMorphInfo(word);

        for (int i = 0; i < wordInfo.size(); i++) {
            String info = wordInfo.get(i);
            if (PARTICLES.stream().noneMatch(info::contains)) {
                return normalForms.get(i);
            }
        }
        return null;
    }

    private List<String> splitIntoWords(String text) {
        return Arrays.stream(text.split("\\P{L}+"))
                .filter(word -> !word.isBlank())
                .collect(Collectors.toList());
    }
}
