package com.pagelens.web.api;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pagelens.core.config.WindsorProperties;
import com.pagelens.core.importer.BusinessSuiteImporter;
import com.pagelens.core.importer.BusinessSuiteImporter.ImportException;
import com.pagelens.core.importer.BusinessSuiteImporter.Report;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.windsor.WindsorAccounts;
import com.pagelens.core.windsor.WindsorAccounts.Found;

/**
 * Read-only data sources for when Meta's App Dashboard is out of reach (docs/META_ACCESS.md): Windsor.ai account
 * discovery and Meta Business Suite CSV import.
 */
@RestController
@RequestMapping("/api/sources")
public class SourcesController {

    /** {@code csvMaxBytes}: uploads above this are refused before parsing. */
    public record SourcesStatus(boolean windsorConfigured, int windsorHistoryDays, long csvMaxBytes) {
    }

    static final long CSV_MAX_BYTES = 20L * 1024 * 1024;

    private final WindsorAccounts windsor;
    private final WindsorProperties windsorProps;
    private final BusinessSuiteImporter importer;

    public SourcesController(WindsorAccounts windsor, WindsorProperties windsorProps, BusinessSuiteImporter importer) {
        this.windsor = windsor;
        this.windsorProps = windsorProps;
        this.importer = importer;
    }

    @GetMapping
    public SourcesStatus status() {
        return new SourcesStatus(windsor.configured(), windsorProps.historyDays(), CSV_MAX_BYTES);
    }

    /** Adds every Instagram account connected in the Windsor.ai workspace. Doesn't sync; use Sync now. */
    @PostMapping("/windsor/accounts")
    public List<Found> discoverWindsor() {
        return windsor.discover(Actor.UI);
    }

    /**
     * Imports a Meta Business Suite content export. {@code accountId} is only needed when the file has no
     * "Account ID" column.
     */
    @PostMapping(path = "/import/business-suite", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Report importBusinessSuite(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long accountId) throws IOException {
        if (file.isEmpty()) {
            throw new ImportException("The file is empty");
        }
        if (file.getSize() > CSV_MAX_BYTES) {
            throw new ImportException("The file is over " + CSV_MAX_BYTES / (1024 * 1024) + " MB; export a shorter date range");
        }
        return importer.importCsv(file.getBytes(), accountId, Actor.UI);
    }
}
