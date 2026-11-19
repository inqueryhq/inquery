package ai.inquery.server.domain.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Official metric definition the assistant must follow when computing it.
 */
@Data
public class CatalogMetricDTO implements Serializable {
    private Long id;
    private Long dataSourceId;
    private String name;
    private List<String> aliases = new ArrayList<>();
    private String description;
    /** schema.table (or database.schema.table) the metric is computed from. */
    private String tableRef;
    /** SQL aggregate expression, e.g. SUM(amount). */
    private String expression;
    /** SQL boolean condition always applied, e.g. status <> 'cancelled'. */
    private String filters;
    /** Grain the metric is defined at, e.g. "per day" or "per order". */
    private String grain;
    private String caveats;
    /** additive | distinct | non_additive, or null. */
    private String aggregation;
}
