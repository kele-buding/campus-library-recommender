package com.campus.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.library.entity.Book;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BookMapper extends BaseMapper<Book> {

    /**
     * 按 book_id 加排他行锁（PostgreSQL 与 H2 均原生支持），用于借还与预约事务串行化。
     */
    @Select("SELECT * FROM book WHERE book_id = #{bookId} FOR UPDATE")
    Book selectByIdForUpdate(@Param("bookId") Long bookId);

    /**
     * 条件扣减可借库存并递增书目版本，数据库层二次防止库存为负。
     */
    @Update("UPDATE book SET available_count = available_count - 1, version = version + 1 "
            + "WHERE book_id = #{bookId} AND available_count > 0")
    int decrementAvailableAndBumpVersion(@Param("bookId") Long bookId);

    /**
     * 归还恢复可借库存并递增书目版本，确保不超过馆藏总数。
     */
    @Update("UPDATE book SET available_count = available_count + 1, version = version + 1 "
            + "WHERE book_id = #{bookId} AND available_count < total_count")
    int incrementAvailableAndBumpVersion(@Param("bookId") Long bookId);

    /**
     * 仅递增书目版本（用于有效预约新增/取消，库存保持不变）。
     */
    @Update("UPDATE book SET version = version + 1 WHERE book_id = #{bookId}")
    int bumpVersionOnly(@Param("bookId") Long bookId);
}
