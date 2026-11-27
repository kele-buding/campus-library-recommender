package com.campus.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.library.entity.Loan;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LoanMapper extends BaseMapper<Loan> {

    /**
     * 按 loan_id 加排他行锁并强制刷新一级缓存，确保读取到并发事务已提交的最新归还状态。
     */
    @Select("SELECT * FROM loan WHERE loan_id = #{loanId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Loan selectByIdForUpdate(@Param("loanId") Long loanId);

    /**
     * 原子条件归还：仅当 returned_on IS NULL 时更新，从数据库层彻底防止并发重复归还。
     */
    @Update("UPDATE loan SET returned_on = #{returnedOn} WHERE loan_id = #{loanId} AND returned_on IS NULL")
    int markReturnedIfActive(@Param("loanId") Long loanId, @Param("returnedOn") LocalDate returnedOn);
}
