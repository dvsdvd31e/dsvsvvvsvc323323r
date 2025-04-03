package searchengine.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;


import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.Lemma;
import searchengine.model.Page;

public interface IndexRepository extends JpaRepository<Index, Integer> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Index i WHERE i.page.site.id = :siteId")
    int deleteBySiteId(@Param("siteId") int siteId);

    Index findByLemmaAndPage(Lemma lemma, Page page);

}
