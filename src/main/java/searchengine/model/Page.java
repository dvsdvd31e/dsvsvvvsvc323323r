package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

@Entity
@Table(
        name = "page",
        indexes = {@jakarta.persistence.Index(name = "idx_path", columnList = "path")}
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(length = 500, nullable = false)
    private String path;


    @Column(nullable = false)
    private int code;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(length = 500)
    private String contentType;

    @Transient
    private String title;

    @Transient
    private String text;

    public String getText() {
        if (content != null) {
            Document doc = Jsoup.parse(content);
            return doc.text();
        }
        return null;
    }
}
