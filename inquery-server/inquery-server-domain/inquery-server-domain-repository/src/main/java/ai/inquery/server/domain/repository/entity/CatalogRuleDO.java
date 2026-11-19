package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Data rule the assistant must follow when writing SQL for a data source.
 */
@Data
@TableName("catalog_rule")
public class CatalogRuleDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long dataSourceId;

    private String ruleText;

    /** Optional schema.table the rule is about. */
    private String tableRef;

    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
