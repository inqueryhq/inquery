package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * Data catalog column information
 * </p>
 *
 * @since 2025-01-27
 */
@Getter
@Setter
@TableName("data_catalog_column")
public class DataCatalogColumnDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Creation time
     */
    private Date gmtCreate;

    /**
     * Modified time
     */
    private Date gmtModified;

    /**
     * Reference to data_catalog_table.id
     */
    private Long tableId;

    /**
     * Column name
     */
    private String columnName;

    /**
     * Column description
     */
    private String columnDescription;

    /**
     * Schema information (JSON format)
     */
    private String schemaInfo;

    /**
     * Example values (JSON array)
     */
    private String exampleValues;

    /**
     * Column ordinal position (DDL order)
     */
    private Integer ordinalPosition;

    /**
     * User-designated event column (values are analytics event names)
     */
    private Boolean isEventColumn;

    /**
     * How the column may be aggregated across rows: additive, distinct or
     * non_additive. Null keeps the stored value on save; empty clears it.
     */
    private String aggregation;
}














