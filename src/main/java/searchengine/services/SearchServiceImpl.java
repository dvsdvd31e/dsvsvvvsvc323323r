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

        List<Page> pagesWithMatches = pages.stream()
                .filter(page -> hasLemmasMatches(page, lemmas))
                .collect(Collectors.toList());

        List<SearchResult> results = pagesWithMatches.stream()
                .map(page -> new SearchResult(
                        page.getSite().getUrl(),
                        page.getSite().getName(),
                        page.getPath(),
                        page.getTitle(),
                        generateSnippet(page.getContent(), lemmas, page.getPath()),
                        calculateRelevance(page, lemmas)
                ))
                .sorted((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()))
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

        return new SearchResponse(true, results.size(), results);
    }

    private boolean hasLemmasMatches(Page page, List<String> lemmas) {
        String content = page.getContent().toLowerCase();
        for (String lemma : lemmas) {
            if (!content.contains(lemma.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    private String generateSnippet(String content, List<String> lemmas, String pagePath) {
        int snippetLength = 200;
        String lowerContent = content.toLowerCase();

        List<Integer> lemmaPositions = new ArrayList<>();
        for (String lemma : lemmas) {
            String lowerLemma = lemma.toLowerCase();
            int index = 0;
            while ((index = lowerContent.indexOf(lowerLemma, index)) != -1) {
                lemmaPositions.add(index);
                index += lowerLemma.length();
            }
        }

        if (lemmaPositions.isEmpty()) {
            return "...Совпадений не найдено...";
        }

        int start = Math.max(lemmaPositions.get(0) - 50, 0);
        int end = Math.min(start + snippetLength, content.length());

        String snippet = content.substring(start, end);

        for (String lemma : lemmas) {
            String lowerLemma = lemma.toLowerCase();
            snippet = snippet.replaceAll("(?i)" + lowerLemma, "<b><a href=\"" + pagePath + "#match-" + lemma.hashCode() + "\">" + "$0" + "</a></b>");
        }

        return "..." + snippet + "...";
    }

    private double calculateRelevance(Page page, List<String> lemmas) {
        String content = page.getContent().toLowerCase();
        double relevance = 0.0;

        for (String lemma : lemmas) {
            String lowerLemma = lemma.toLowerCase();
            relevance += content.split(lowerLemma, -1).length - 1;
        }

        return relevance;
    }
}
