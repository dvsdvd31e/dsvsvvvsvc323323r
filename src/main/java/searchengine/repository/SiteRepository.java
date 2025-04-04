package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Site;
import searchengine.model.Page;
import searchengine.model.IndexingStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<Site,List> {

    Site findByUrl(String url);

    @Modifying
    @Transactional
    void delete(Site site);

}