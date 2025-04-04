package searchengine.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.config.SitesList;
import java.time.LocalDateTime;
import searchengine.model.IndexingStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.io.IOException;

@Service
public class PageIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(PageIndexingService.class);

    @Autowired
    private PageRepository pageRepository;

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private SitesList sitesList; // Внедрение конфигурации

    // Метод для индексации страницы по URL
    public void indexPage(String url) {
        try {
            // Проверяем, что URL находится в списке сайтов из конфигурации
            if (!isValidSite(url)) {
                logger.warn("Сайт с таким URL не найден в списке конфигурации: {}", url);
                return; // Пропускаем URL, если его нет в конфигурации
            }

            // Извлекаем информацию о сайте по его URL
            Site site = siteRepository.findByUrl(url);
            if (site == null) {
                logger.info("Сайт с таким URL не найден. Создаём новый сайт для URL: {}", url);

                // Создаём новый объект Site и устанавливаем необходимые поля
                site = new Site();
                site.setUrl(url);
                site.setName("Unknown Site"); // Здесь можно установить имя сайта, если оно известно
                site.setStatus(IndexingStatus.INDEXING); // Устанавливаем статус как INDEXING
                site.setStatusTime(LocalDateTime.now()); // Устанавливаем текущее время как статусное время

                siteRepository.save(site);
                logger.info("Новый сайт успешно добавлен: {}", url);
            }

            // Загружаем HTML-страницу
            Document document = Jsoup.connect(url).get();
            String title = document.title();
            String content = document.html();
            String path = document.location();  // Получаем путь страницы из URL

            // Проверяем, существует ли страница с данным path и siteId
            boolean exists = pageRepository.existsByPathAndSiteId(path, site.getId());

            if (!exists) {
                // Если страница не существует, создаем новую
                Page page = new Page();
                page.setSite(site);
                page.setPath(path);
                page.setContent(content);
                page.setCode(200);  // Успешный код
                page.setTitle(title);

                // Сохраняем страницу в базе данных
                pageRepository.save(page);
                logger.info("Страница успешно проиндексирована: {}", url);
            } else {
                logger.info("Страница с таким URL уже существует, пропускаем: {}", url);
            }
        } catch (IOException e) {
            logger.error("Ошибка при индексации страницы: {}", url, e);
        }
    }

    // Метод для проверки, есть ли сайт в списке конфигурации
    private boolean isValidSite(String url) {
        return sitesList.getSites().stream()
                .anyMatch(configSite -> configSite.getUrl().equals(url));
    }
}
