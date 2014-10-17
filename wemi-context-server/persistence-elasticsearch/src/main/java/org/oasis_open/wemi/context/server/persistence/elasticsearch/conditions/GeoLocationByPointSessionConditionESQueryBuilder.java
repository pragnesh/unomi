package org.oasis_open.wemi.context.server.persistence.elasticsearch.conditions;

import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.oasis_open.wemi.context.server.api.conditions.Condition;

/**
 * Created by loom on 12.09.14.
 */
public class GeoLocationByPointSessionConditionESQueryBuilder implements ConditionESQueryBuilder {
    @Override
    public FilterBuilder buildFilter(Condition condition, ConditionESQueryBuilderDispatcher dispatcher) {
        String latitude = (String) condition.getParameterValues().get("latitude");
        String longitude = (String) condition.getParameterValues().get("longitude");
        String distance = (String) condition.getParameterValues().get("distance");
        return FilterBuilders.geoDistanceFilter("location")
                .lat(Double.parseDouble(latitude))
                .lon(Double.parseDouble(longitude))
                .distance(distance);
    }

}
