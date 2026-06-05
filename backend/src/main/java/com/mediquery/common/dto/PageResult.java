package com.mediquery.common.dto;

import lombok.Data;
import java.util.List;

/**
 * 分页统一返回体
 */
@Data
public class PageResult<T> {
    private long total;
    private long page;
    private long size;
    private List<T> records;

    public PageResult() {}
    public PageResult(long total, long page, long size, List<T> records) {
        this.total = total; this.page = page; this.size = size; this.records = records;
    }

    public static <T> PageResult<T> of(long total, long page, long size, List<T> records) {
        return new PageResult<>(total, page, size, records);
    }
}
