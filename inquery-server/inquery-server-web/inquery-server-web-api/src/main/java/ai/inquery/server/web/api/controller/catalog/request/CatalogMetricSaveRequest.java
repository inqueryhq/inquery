package ai.inquery.server.web.api.controller.catalog.request;

import ai.inquery.server.domain.api.model.CatalogMetricDTO;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Metric save request. Extends {@link DataSourceBaseRequest} so the
 * connection aspect opens the data source; databaseName / schemaName are the
 * defaults for a table reference that does not include them, and the
 * connection is used to check that the metric's columns exist.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CatalogMetricSaveRequest extends DataSourceBaseRequest {
    private CatalogMetricDTO metric;
}
