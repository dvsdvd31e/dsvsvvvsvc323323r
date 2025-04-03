package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import java.util.ArrayList;
import searchengine.config.ConfigSite;
import java.util.List;
import java.util.Random;

import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;
import java.net.URL;
import java.net.HttpURLConnection;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final Random random = new Random();
    private final SitesList sites;
    private final RestTemplate restTemplate = new RestTemplate();  // Используем RestTemplate для HTTP-запросов

    @Override
    public StatisticsResponse getStatistics() {
        String[] statuses = { "INDEXED", "FAILED", "INDEXING" };
        String[] errors = {
                "Ошибка индексации: главная страница сайта не доступна",
                "Ошибка индексации: сайт не доступен",
                ""
        };

        // Создаем объект для общего состояния статистики
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(true); // Индексация всегда true по умолчанию

        // Списки для подробной статистики
        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<ConfigSite> sitesList = sites.getSites();

        // Проходим по всем сайтам
        for (int i = 0; i < sitesList.size(); i++) {
            ConfigSite site = sitesList.get(i);
            DetailedStatisticsItem item = new DetailedStatisticsItem();

            // Заполняем информацию о каждом сайте
            item.setName(site.getName());
            item.setUrl(site.getUrl());

            // Проверка доступности сайта и главной страницы
            String status = checkSiteAvailability(site.getUrl()) ? "INDEXED" : "FAILED";
            String error = status.equals("FAILED") ? "Ошибка индексации: сайт не доступен" : "";

            // Генерация случайных данных для страниц и лемм
            int pages = random.nextInt(1_000);
            int lemmas = pages * random.nextInt(1_000);
            item.setPages(pages);
            item.setLemmas(lemmas);

            // Статус
            item.setStatus(status);

            // Добавляем ошибку, если сайт не доступен
            if (!error.isEmpty()) {
                item.setError(error);
            }

            // Время последнего обновления статуса (рандомное время)
            item.setStatusTime(System.currentTimeMillis() - (random.nextInt(10_000)));

            // Обновляем общие статистики
            total.setPages(total.getPages() + pages);
            total.setLemmas(total.getLemmas() + lemmas);

            // Добавляем сайт в подробную статистику
            detailed.add(item);
        }

        // Формируем конечный объект ответа
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);

        // Устанавливаем результат как true
        response.setStatistics(data);
        response.setResult(true);

        return response;
    }

    // Метод для проверки доступности сайта
    private boolean checkSiteAvailability(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // Тайм-аут соединения
            connection.setReadTimeout(5000);    // Тайм-аут чтения
            connection.connect();

            // Если статус ответа не 2xx, то считаем, что сайт не доступен
            return connection.getResponseCode() == HttpStatus.OK.value();
        } catch (Exception e) {
            return false;  // В случае ошибки соединения или если сайт не доступен
        }
    }
}
