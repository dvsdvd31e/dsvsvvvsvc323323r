package searchengine.services;

import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.search.SearchResult;
import searchengine.repository.PageRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.IndexRepository;
import searchengine.utils.LemmaProcessor;
import searchengine.model.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements SearchService {
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaProcessor lemmaProcessor;

    public SearchServiceImpl(PageRepository pageRepository, LemmaRepository lemmaRepository, IndexRepository indexRepository, LemmaProcessor lemmaProcessor) {
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.lemmaProcessor = lemmaProcessor;
    }

    @Override
    public SearchResponse search(String query, String site, int offset, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchResponse("Задан пустой поисковый запрос");
        }

        List<String> lemmas = lemmaProcessor.extractLemmas(query);
        if (lemmas.isEmpty()) {
            return new SearchResponse("Не удалось обработать запрос");
        }

        List<Page> pages;
        if (site == null || site.isEmpty()) {
            pages = pageRepository.findPagesByLemmas(lemmas);
        } else {
            pages = pageRepository.findPagesByLemmas(lemmas, site);
        }

        List<SearchResult> results = pages.stream()
                .map(page -> new SearchResult(
                        page.getSite().getUrl(),
                        page.getSite().getName(),
                        page.getPath(),
                        page.getTitle(),
                        generateSnippet(page.getContent(), lemmas, page.getPath()),  // Передаем URL страницы
                        calculateRelevance(page, lemmas)
                ))
                .sorted((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()))
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

        return new SearchResponse(true, results.size(), results);
    }

    private String generateSnippet(String content, List<String> lemmas, String pagePath) {
        int snippetLength = 200; // Длина сниппета
        String lowerContent = content.toLowerCase();  // Приводим контент страницы к нижнему регистру

        // Выводим леммы и содержимое для отладки
        System.out.println("Леммы для поиска: " + lemmas);
        System.out.println("Контент страницы: " + content);

        // Создаем список всех позиций, где встречаются леммы
        List<Integer> lemmaPositions = new ArrayList<>();
        for (String lemma : lemmas) {
            // Приводим лемму к нижнему регистру для поиска
            String lowerLemma = lemma.toLowerCase();

            int index = 0;
            while ((index = lowerContent.indexOf(lowerLemma, index)) != -1) {
                lemmaPositions.add(index);
                index += lowerLemma.length();
            }
        }

        if (lemmaPositions.isEmpty()) {
            System.out.println("Совпадений не найдено для лемм на странице.");
            return "...Совпадений не найдено...";
        }

        // Выбираем позицию для начала сниппета, чтобы она была в пределах первого найденного совпадения
        int bestIndex = lemmaPositions.stream().min(Integer::compareTo).orElse(0);
        int start = Math.max(bestIndex - 50, 0);
        int end = Math.min(start + snippetLength, content.length());

        // Выделяем совпадения в <b>
        String snippet = content.substring(start, end);
        System.out.println("Выделенный сниппет: " + snippet);

        // Подсвечиваем леммы в сниппете
        for (String lemma : lemmas) {
            String lowerLemma = lemma.toLowerCase();
            snippet = snippet.replaceAll("(?i)" + lowerLemma, "<b><a href=\"" + pagePath + "#match-" + lemma.hashCode() + "\">" + "$0" + "</a></b>");
        }

        return "..." + snippet + "...";
    }

    private double calculateRelevance(Page page, List<String> lemmas) {
        return 1.0;
    }
}
