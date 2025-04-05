package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.ZoneOffset;
import searchengine.repository.LemmaRepository;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.repository.SiteRepository;
import searchengine.repository.PageRepository;
import java.util.ArrayList;
import searchengine.model.Site;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final SitesList sitesList;

    @Override
    public StatisticsResponse getStatistics() {
        List<Site> siteList = siteRepository.findAll();

        TotalStatistics total = new TotalStatistics();
        total.setSites(siteList.size());
        total.setIndexing(false);

        List<DetailedStatisticsItem> detailed = new ArrayList<>();

        for (Site site : siteList) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            item.setPages(pageRepository.countBySite(site));
            item.setLemmas(lemmaRepository.countBySite(site));
            item.setStatus(site.getStatus().toString());
            item.setError(site.getLastError() != null ? site.getLastError() : "");
            item.setStatusTime(site.getStatusTime().toInstant(ZoneOffset.UTC).toEpochMilli());

            total.setPages(total.getPages() + item.getPages());
            total.setLemmas(total.getLemmas() + item.getLemmas());

            detailed.add(item);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);

        return response;
    }

    @Override
    public void updateStatistics(StatisticsResponse response) {
        System.out.println("Обновление статистики...");
        System.out.println("Обновленные данные: " + response);
    }

    @Override
    public void updateSiteStatistics(Site site) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        item.setName(site.getName());
        item.setUrl(site.getUrl());
        item.setPages(pageRepository.countBySite(site));
        item.setLemmas(lemmaRepository.countBySite(site));
        item.setStatus(site.getStatus().toString());
        item.setError(site.getLastError() != null ? site.getLastError() : "");
        item.setStatusTime(site.getStatusTime().toInstant(ZoneOffset.UTC).toEpochMilli());

        TotalStatistics total = new TotalStatistics();
        total.setSites(1);  // Один сайт
        total.setPages(item.getPages());
        total.setLemmas(item.getLemmas());
        total.setIndexing(false);

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(List.of(item));
        response.setStatistics(data);
        response.setResult(true);

        updateStatistics(response);
        System.out.println("Обновление статистики для сайта: " + site.getName());
    }
}
