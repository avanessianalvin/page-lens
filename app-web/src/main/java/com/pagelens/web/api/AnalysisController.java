package com.pagelens.web.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.analysis.AnalysisModels.CaptionStats;
import com.pagelens.core.analysis.AnalysisModels.FollowerGrowth;
import com.pagelens.core.analysis.AnalysisModels.FormatComparison;
import com.pagelens.core.analysis.AnalysisModels.Metric;
import com.pagelens.core.analysis.AnalysisModels.Overview;
import com.pagelens.core.analysis.AnalysisModels.PostingTimes;
import com.pagelens.core.analysis.AnalysisModels.RankedPosts;
import com.pagelens.core.analysis.AnalysisService;

/**
 * Analysis endpoints (plan section 8). They read local data only, so they cost no Instagram rate budget.
 * Periods: {@code period} = last_7_days | last_30_days | last_90_days | last_365_days | all, or {@code from}/{@code to}
 * days (YYYY-MM-DD, owner's time zone, inclusive).
 */
@RestController
@RequestMapping("/api/accounts/{id}")
public class AnalysisController {

    private final AnalysisService analysis;

    public AnalysisController(AnalysisService analysis) {
        this.analysis = analysis;
    }

    @GetMapping("/overview")
    public Overview overview(@PathVariable long id) {
        return analysis.overview(id);
    }

    /** Top (order=top) or bottom (order=bottom) posts by a metric. */
    @GetMapping("/analysis/top-posts")
    public RankedPosts topPosts(@PathVariable long id, @RequestParam(defaultValue = "reach") String metric,
                                @RequestParam(defaultValue = "10") int count,
                                @RequestParam(defaultValue = "top") String order,
                                @RequestParam(required = false) String period, @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        return analysis.ranked(id, Metric.parse(metric), count, "bottom".equalsIgnoreCase(order),
                analysis.range(period, from, to));
    }

    @GetMapping("/analysis/formats")
    public FormatComparison formats(@PathVariable long id, @RequestParam(required = false) String period,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analysis.formats(id, analysis.range(period, from, to));
    }

    @GetMapping("/analysis/timing")
    public PostingTimes timing(@PathVariable long id, @RequestParam(defaultValue = "engagement_rate") String metric,
                               @RequestParam(required = false) String period,
                               @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analysis.postingTimes(id, Metric.parse(metric), analysis.range(period, from, to));
    }

    @GetMapping("/analysis/growth")
    public FollowerGrowth growth(@PathVariable long id, @RequestParam(required = false) String period,
                                 @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analysis.growth(id, analysis.range(period, from, to, "last_30_days"));
    }

    @GetMapping("/analysis/captions")
    public CaptionStats captions(@PathVariable long id, @RequestParam(required = false) String period,
                                 @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analysis.captions(id, analysis.range(period, from, to));
    }
}
