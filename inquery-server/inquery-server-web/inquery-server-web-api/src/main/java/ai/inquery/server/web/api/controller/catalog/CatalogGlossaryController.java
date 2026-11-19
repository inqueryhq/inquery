package ai.inquery.server.web.api.controller.catalog;

import ai.inquery.server.domain.api.model.CatalogMetricDTO;
import ai.inquery.server.domain.api.model.CatalogTermDTO;
import ai.inquery.server.domain.core.catalog.glossary.CatalogGlossaryService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.catalog.request.CatalogMetricSaveRequest;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Business glossary terms and official metric definitions, per data source.
 */
@RestController
@ConnectionInfoAspect
@RequestMapping("/api/catalog/glossary")
public class CatalogGlossaryController {

    @Autowired
    private CatalogGlossaryService glossaryService;

    @GetMapping("/terms")
    public DataResult<List<CatalogTermDTO>> listTerms(@RequestParam Long dataSourceId) {
        return DataResult.of(glossaryService.listTerms(dataSourceId));
    }

    @PostMapping("/terms")
    public DataResult<CatalogTermDTO> saveTerm(@RequestBody CatalogTermDTO term) {
        return glossaryService.saveTerm(term);
    }

    @DeleteMapping("/terms/{id}")
    public ActionResult deleteTerm(@PathVariable Long id) {
        glossaryService.deleteTerm(id);
        return ActionResult.isSuccess();
    }

    @GetMapping("/rules")
    public DataResult<List<ai.inquery.server.domain.api.model.CatalogRuleDTO>> listRules(@RequestParam Long dataSourceId) {
        return DataResult.of(glossaryService.listRules(dataSourceId));
    }

    @PostMapping("/rules")
    public DataResult<ai.inquery.server.domain.api.model.CatalogRuleDTO> saveRule(
            @RequestBody ai.inquery.server.domain.api.model.CatalogRuleDTO rule) {
        return glossaryService.saveRule(rule);
    }

    @DeleteMapping("/rules/{id}")
    public ActionResult deleteRule(@PathVariable Long id) {
        glossaryService.deleteRule(id);
        return ActionResult.isSuccess();
    }

    @GetMapping("/metrics")
    public DataResult<List<CatalogMetricDTO>> listMetrics(@RequestParam Long dataSourceId) {
        return DataResult.of(glossaryService.listMetrics(dataSourceId));
    }

    @PostMapping("/metrics")
    public DataResult<CatalogMetricDTO> saveMetric(@RequestBody CatalogMetricSaveRequest request) {
        CatalogMetricDTO metric = request.getMetric();
        if (metric != null) metric.setDataSourceId(request.getDataSourceId());
        return glossaryService.saveMetric(metric, request.getDatabaseName(), request.getSchemaName());
    }

    @DeleteMapping("/metrics/{id}")
    public ActionResult deleteMetric(@PathVariable Long id) {
        glossaryService.deleteMetric(id);
        return ActionResult.isSuccess();
    }
}
