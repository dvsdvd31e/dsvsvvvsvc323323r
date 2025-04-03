package searchengine;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;

import java.util.List;

public class LemmatizationDemo {
    public static void main(String[] args) {
        try {
            LuceneMorphology russianMorphology = new RussianLuceneMorphology();
            LuceneMorphology englishMorphology = new EnglishLuceneMorphology();

            String russianWord = "леса";
            String englishWord = "running";

            System.out.println("Лемматизация для русского слова: " + russianWord);
            List<String> russianBaseForms = russianMorphology.getNormalForms(russianWord);
            russianBaseForms.forEach(System.out::println);

            System.out.println();

            System.out.println("Лемматизация для английского слова: " + englishWord);
            List<String> englishBaseForms = englishMorphology.getNormalForms(englishWord);
            englishBaseForms.forEach(System.out::println);

        } catch (Exception e) {
            System.err.println("Ошибка при лемматизации: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
