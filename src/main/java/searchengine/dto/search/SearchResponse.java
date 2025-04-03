package searchengine.dto.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    private boolean result;
    private int count;
    private List<SearchResult> data;
    private String error;

    public SearchResponse(boolean result, int count, List<SearchResult> data) {
        this.result = result;
        this.count = count;
        this.data = data;
        this.error = null;
    }

    public SearchResponse(String error) {
        this.result = false;
        this.count = 0;
        this.data = null;
        this.error = error;
    }
}
