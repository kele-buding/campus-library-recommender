package com.campus.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;

@TableName("reminder")
public class Reminder {

    @TableId(value = "loan_id", type = IdType.INPUT)
    private Long loanId;
    private LocalDate markedOn;
    private Long clockVersion;

    public Long getLoanId() {
        return loanId;
    }

    public void setLoanId(Long loanId) {
        this.loanId = loanId;
    }

    public LocalDate getMarkedOn() {
        return markedOn;
    }

    public void setMarkedOn(LocalDate markedOn) {
        this.markedOn = markedOn;
    }

    public Long getClockVersion() {
        return clockVersion;
    }

    public void setClockVersion(Long clockVersion) {
        this.clockVersion = clockVersion;
    }
}
