package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import jakarta.persistence.Index;


@Entity
@Table(name = "lemma", indexes = {
        @Index(name = "idx_lemma", columnList = "lemma"),
        @Index(name = "idx_site_id", columnList = "site_id"),
        @Index(name = "idx_lemma_site", columnList = "lemma, site_id", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "site")
@EqualsAndHashCode(of = {"lemma", "site"})
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(name = "lemma", nullable = false, length = 500)
    private String lemma;

    @Column(name = "frequency", nullable = false)
    private Integer frequency = 1;

}
