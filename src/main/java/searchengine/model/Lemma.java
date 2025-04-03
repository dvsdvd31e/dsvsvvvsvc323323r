package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "lemma")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    private String lemma;

    @Column(nullable = false)
    private int frequency;


}
