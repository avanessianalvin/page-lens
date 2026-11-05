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

    /** Everything a write action needs to know about one comment, including its media's account. */
    public record Target(long id, String igCommentId, Long parentId, String parentIgCommentId, boolean hidden,
                         String username, long mediaId, String igMediaId, long accountId, String accountUsername) {
    }

    public java.util.Optional<Target> findTarget(long commentId) {
        return jdbc.sql("""
                        SELECT c.id, c.ig_comment_id, c.parent_id, p.ig_comment_id AS parent_ig_id, c.hidden, c.username,
                               m.id AS media_id, m.ig_media_id, a.id AS account_id, a.username AS account_username
                        FROM comment c
                        JOIN media m ON m.id = c.media_id
                        JOIN ig_account a ON a.id = m.account_id
                        LEFT JOIN comment p ON p.id = c.parent_id
                        WHERE c.id = :id
                        """)
                .param("id", commentId)
                .query((rs, n) -> new Target(rs.getLong("id"), rs.getString("ig_comment_id"), lng(rs, "parent_id"),
                        rs.getString("parent_ig_id"), rs.getBoolean("hidden"), rs.getString("username"),
                        rs.getLong("media_id"), rs.getString("ig_media_id"), rs.getLong("account_id"),
                        rs.getString("account_username")))
                .optional();
    }

    /** Stores a reply PageLens just posted, so it shows up before the next sync. */
    public long insertOwnReply(long mediaId, long parentId, String igCommentId, String username, String text,
                               Instant postedAt) {
        return jdbc.sql("""
                        INSERT INTO comment (media_id, ig_comment_id, parent_id, username, text, like_count, posted_at, hidden)
                        VALUES (:media, :igId, :parent, :username, :text, 0, :posted, FALSE)
                        ON CONFLICT (ig_comment_id) DO UPDATE SET text = EXCLUDED.text
                        RETURNING id
                        """)
                .param("media", mediaId).param("igId", igCommentId).param("parent", parentId)
                .param("username", username).param("text", text).param("posted", ts(postedAt))
                .query(Long.class).single();
    }

    public void setHidden(long commentId, boolean hidden) {
        jdbc.sql("UPDATE comment SET hidden = :h, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("h", hidden).param("id", commentId).update();
    }

    /** Deletes the comment and (via ON DELETE CASCADE) its replies. */
    public void delete(long commentId) {
        jdbc.sql("DELETE FROM comment WHERE id = :id").param("id", commentId).update();
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
