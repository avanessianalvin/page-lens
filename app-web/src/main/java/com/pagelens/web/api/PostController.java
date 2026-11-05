package com.pagelens.web.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.config.PageLensProperties;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.CommentRepository;
import com.pagelens.core.repo.CommentRepository.Comment;
import com.pagelens.core.repo.InsightRepository;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.repo.MediaRepository.Page;
import com.pagelens.core.repo.MediaRepository.Post;
import com.pagelens.core.repo.MediaRepository.PostQuery;

@RestController
@RequestMapping("/api")
public class PostController {

    public record PostDetail(Post post, String accountUsername, List<Snapshot> history) {
    }

    private final MediaRepository media;
    private final InsightRepository insights;
    private final CommentRepository comments;
    private final AccountRepository accounts;
    private final PageLensProperties props;

    public PostController(MediaRepository media, InsightRepository insights, CommentRepository comments,
                          AccountRepository accounts, PageLensProperties props) {
        this.media = media;
        this.insights = insights;
        this.comments = comments;
        this.accounts = accounts;
        this.props = props;
    }

    /**
     * Posts for an account. {@code from}/{@code to} are calendar days in the owner's time zone (inclusive).
     * {@code type} matches product type (FEED, REELS, STORY) or media type (IMAGE, VIDEO, CAROUSEL_ALBUM).
     */
    @GetMapping("/accounts/{id}/posts")
    public Page<Post> list(@PathVariable long id,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) String type,
                           @RequestParam(defaultValue = "posted_at") String sort,
                           @RequestParam(defaultValue = "desc") String dir,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "25") int size) {
        accounts.findById(id).orElseThrow(() -> new NotFoundException("Account " + id));
        if (!MediaRepository.SORT_KEYS.contains(sort)) {
            throw new IllegalArgumentException("sort must be one of " + MediaRepository.SORT_KEYS);
        }
        var zone = props.zoneId();
        int pageSize = Math.min(Math.max(size, 1), 200);
        return media.query(new PostQuery(id,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                type, sort, "asc".equalsIgnoreCase(dir), pageSize, Math.max(page, 0) * pageSize));
    }

    @GetMapping("/posts/{id}")
    public PostDetail get(@PathVariable long id) {
        Post post = media.findPost(id).orElseThrow(() -> new NotFoundException("Post " + id));
        String username = accounts.findById(post.accountId()).map(AccountRepository.Account::username).orElse(null);
        return new PostDetail(post, username, insights.history(id));
    }

    @GetMapping("/posts/{id}/comments")
    public List<Comment> comments(@PathVariable long id, @RequestParam(defaultValue = "100") int limit) {
        media.findPost(id).orElseThrow(() -> new NotFoundException("Post " + id));
        return comments.threads(id, Math.min(Math.max(limit, 1), 1000));
    }
}
