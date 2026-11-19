package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Business glossary term: a canonical value and its aliases.
 */
@Data
@TableName("catalog_term")
public class CatalogTermDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long dataSourceId;

    /** Exact value as stored in the data. */
    private String canonicalValue;

    /** JSON array of aliases. */
    private String aliases;

    /** Optional schema.table.column holding the value. */
    private String columnRef;

    private String note;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
