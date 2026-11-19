package ai.inquery.server.domain.api.model;

import lombok.Data;

import java.io.Serializable;

/**
 * Data rule the assistant must follow when writing SQL, e.g.
 * "Never count orders with status 'pending'".
 */
@Data
public class CatalogRuleDTO implements Serializable {
    private Long id;
    private Long dataSourceId;
    private String ruleText;
    /** Optional schema.table the rule is about. */
    private String tableRef;
    private Boolean enabled = Boolean.TRUE;
    /** Set on save when the rule reads like a description rather than an instruction. */
    private String styleWarning;
}
