package com.campus.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("purchase_result")
public class PurchaseResult {

    @TableId(value = "book_id", type = IdType.INPUT)
    private Long bookId;
    private Long version;
    private Integer suggestedQty;
    private OffsetDateTime computedAt;

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Integer getSuggestedQty() {
        return suggestedQty;
    }

    public void setSuggestedQty(Integer suggestedQty) {
        this.suggestedQty = suggestedQty;
    }

    public OffsetDateTime getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(OffsetDateTime computedAt) {
        this.computedAt = computedAt;
    }
}
