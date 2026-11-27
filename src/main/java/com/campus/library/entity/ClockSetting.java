package com.campus.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;

@TableName("clock_setting")
public class ClockSetting {

    @TableId(value = "singleton_id", type = IdType.INPUT)
    private Short singletonId;
    private LocalDate simDate;
    private Long clockVersion;

    public Short getSingletonId() {
        return singletonId;
    }

    public void setSingletonId(Short singletonId) {
        this.singletonId = singletonId;
    }

    public LocalDate getSimDate() {
        return simDate;
    }

    public void setSimDate(LocalDate simDate) {
        this.simDate = simDate;
    }

    public Long getClockVersion() {
        return clockVersion;
    }

    public void setClockVersion(Long clockVersion) {
        this.clockVersion = clockVersion;
    }
}
