package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;


@Entity
@Table(
        name = "site",
        indexes = {@jakarta.persistence.Index(name = "idx_url", columnList = "url")},
        uniqueConstraints = {@UniqueConstraint(columnNames = "url")}
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IndexingStatus status;

    @Column(name = "status_time", nullable = false)
    private LocalDateTime statusTime;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(length = 500, nullable = false, unique = true)
    private String url;

    @Column(length = 500, nullable = false)
    private String name;

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Page> pages = new ArrayList<>();
}
