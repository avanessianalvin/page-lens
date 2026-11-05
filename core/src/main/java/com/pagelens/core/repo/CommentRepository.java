package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.lng;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pagelens.core.instagram.IgModels.IgComment;

/** Comments may contain personal data: they stay in the local database and are never logged. */
@Repository
public class CommentRepository {

    public record Comment(long id, String igCommentId, String username, String text, Long likeCount,
                          Instant postedAt, boolean hidden, List<Comment> replies) {
    }

    private final JdbcClient jdbc;

    public CommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Upserts a top-level comment and its replies; returns the number of rows written. */
    public int upsertThread(long mediaId, IgComment c) {
        long parentId = upsert(mediaId, null, c);
        int n = 1;
        for (IgComment reply : c.replies()) {
            upsert(mediaId, parentId, reply);
            n++;
        }
        return n;
    }

    private long upsert(long mediaId, Long parentId, IgComment c) {
        return jdbc.sql("""
                        INSERT INTO comment (media_id, ig_comment_id, parent_id, username, text, like_count, posted_at, hidden)
                        VALUES (:media, :igId, :parent, :username, :text, :likes, :posted, COALESCE(:hidden, FALSE))
                        ON CONFLICT (ig_comment_id) DO UPDATE SET
                            text = EXCLUDED.text, like_count = EXCLUDED.like_count,
                            hidden = COALESCE(:hidden, comment.hidden), updated_at = CURRENT_TIMESTAMP
                        RETURNING id
                        """)
                .param("media", mediaId).param("igId", c.id()).param("parent", parentId)
                .param("username", c.username()).param("text", c.text()).param("likes", c.likeCount())
                .param("posted", ts(c.timestamp())).param("hidden", c.hidden())
                .query(Long.class).single();
    }

    /** Top-level comments newest first, each with its replies oldest first. */
    public List<Comment> threads(long mediaId, int limit) {
        List<Row> rows = jdbc.sql("""
                        SELECT id, ig_comment_id, parent_id, username, text, like_count, posted_at, hidden
                        FROM comment WHERE media_id = :media ORDER BY posted_at
                        """)
                .param("media", mediaId).query(CommentRepository::row).list();
        Map<Long, List<Comment>> replies = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.parentId() != null) {
                replies.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r.toComment(List.of()));
            }
        }
        List<Comment> top = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0 && top.size() < limit; i--) {
            Row r = rows.get(i);
            if (r.parentId() == null) {
                top.add(r.toComment(replies.getOrDefault(r.id(), List.of())));
            }
        }
        return top;
    }

    private record Row(long id, String igCommentId, Long parentId, String username, String text, Long likeCount,
                       Instant postedAt, boolean hidden) {
        Comment toComment(List<Comment> replies) {
            return new Comment(id, igCommentId, username, text, likeCount, postedAt, hidden, replies);
        }
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("ig_comment_id"), lng(rs, "parent_id"), rs.getString("username"),
                rs.getString("text"), lng(rs, "like_count"), instant(rs, "posted_at"), rs.getBoolean("hidden"));
    }
}
