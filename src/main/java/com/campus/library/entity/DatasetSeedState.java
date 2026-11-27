package com.campus.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("dataset_seed_state")
public class DatasetSeedState {

    @TableId(value = "singleton_id", type = IdType.INPUT)
    private Short singletonId;
    private String datasetVersion;
    private OffsetDateTime seededAt;

    public Short getSingletonId() {
        return singletonId;
    }

    public void setSingletonId(Short singletonId) {
        this.singletonId = singletonId;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public OffsetDateTime getSeededAt() {
        return seededAt;
    }

    public void setSeededAt(OffsetDateTime seededAt) {
        this.seededAt = seededAt;
    }
}
