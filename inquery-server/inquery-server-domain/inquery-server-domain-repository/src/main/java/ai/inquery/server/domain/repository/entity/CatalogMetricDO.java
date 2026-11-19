package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Official metric definition.
 */
@Data
@TableName("catalog_metric")
public class CatalogMetricDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long dataSourceId;

    private String name;

    /** JSON array of aliases. */
    private String aliases;

    private String description;

    /** schema.table (or database.schema.table) the metric is computed from. */
    private String tableRef;

    /** SQL aggregate expression. */
    private String expression;

    /** SQL boolean condition always applied. */
    private String filters;

    private String grain;

    private String caveats;

    /** additive | distinct | non_additive, or null. */
    private String aggregation;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
