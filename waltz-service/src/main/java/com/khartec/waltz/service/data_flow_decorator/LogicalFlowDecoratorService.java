/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016  Khartec Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.khartec.waltz.service.data_flow_decorator;


import com.khartec.waltz.data.application.ApplicationIdSelectorFactory;
import com.khartec.waltz.data.data_flow_decorator.LogicalFlowDecoratorDao;
import com.khartec.waltz.data.data_type.DataTypeIdSelectorFactory;
import com.khartec.waltz.data.logical_flow.LogicalFlowDao;
import com.khartec.waltz.model.*;
import com.khartec.waltz.model.changelog.ImmutableChangeLog;
import com.khartec.waltz.model.data_flow_decorator.LogicalFlowDecorator;
import com.khartec.waltz.model.data_flow_decorator.DecoratorRatingSummary;
import com.khartec.waltz.model.data_flow_decorator.ImmutableLogicalFlowDecorator;
import com.khartec.waltz.model.logical_flow.LogicalFlow;
import com.khartec.waltz.model.rating.AuthoritativenessRating;
import com.khartec.waltz.service.changelog.ChangeLogService;
import com.khartec.waltz.service.usage_info.DataTypeUsageService;
import org.jooq.Record1;
import org.jooq.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.khartec.waltz.common.Checks.checkNotNull;
import static com.khartec.waltz.common.CollectionUtilities.map;
import static com.khartec.waltz.model.EntityKind.DATA_TYPE;

@Service
public class LogicalFlowDecoratorService {

    private final LogicalFlowDecoratorDao logicalFlowDecoratorDao;
    private final LogicalFlowDecoratorRatingsService ratingsService;
    private final ApplicationIdSelectorFactory applicationIdSelectorFactory;
    private final DataTypeIdSelectorFactory dataTypeIdSelectorFactory;
    private final DataTypeUsageService dataTypeUsageService;
    private final LogicalFlowDao logicalFlowDao;
    private final ChangeLogService changeLogService;


    @Autowired
    public LogicalFlowDecoratorService(LogicalFlowDecoratorDao logicalFlowDecoratorDao,
                                       LogicalFlowDecoratorRatingsService ratingsService,
                                       ApplicationIdSelectorFactory applicationIdSelectorFactory,
                                       DataTypeIdSelectorFactory dataTypeIdSelectorFactory,
                                       DataTypeUsageService dataTypeUsageService,
                                       LogicalFlowDao logicalFlowDao,
                                       ChangeLogService changeLogService) {

        checkNotNull(logicalFlowDecoratorDao, "logicalFlowDecoratorDao cannot be null");
        checkNotNull(applicationIdSelectorFactory, "applicationIdSelectorFactory cannot be null");
        checkNotNull(ratingsService, "ratingsService cannot be null");
        checkNotNull(dataTypeIdSelectorFactory, "dataTypeIdSelectorFactory cannot be null");
        checkNotNull(dataTypeUsageService, "dataTypeUsageService cannot be null");
        checkNotNull(logicalFlowDao, "logicalFlowDao cannot be null");
        checkNotNull(changeLogService, "changeLogService cannot be null");

        this.logicalFlowDecoratorDao = logicalFlowDecoratorDao;
        this.ratingsService = ratingsService;
        this.applicationIdSelectorFactory = applicationIdSelectorFactory;
        this.dataTypeIdSelectorFactory = dataTypeIdSelectorFactory;
        this.dataTypeUsageService = dataTypeUsageService;
        this.logicalFlowDao = logicalFlowDao;
        this.changeLogService = changeLogService;
    }


    // --- FINDERS ---

    public List<LogicalFlowDecorator> findByFlowIds(Collection<Long> flowIds) {
        checkNotNull(flowIds, "flowIds cannot be null");
        return logicalFlowDecoratorDao.findByFlowIds(flowIds);
    }


    public List<LogicalFlowDecorator> findByIdSelectorAndKind(IdSelectionOptions options,
                                                              EntityKind decoratorEntityKind) {
        checkNotNull(options, "options cannot be null");
        checkNotNull(decoratorEntityKind, "decoratorEntityKind cannot be null");

        switch (options.entityReference().kind()) {
            case APPLICATION:
            case APP_GROUP:
            case ORG_UNIT:
            case PROCESS:
            case PERSON:
                Select<Record1<Long>> selector = applicationIdSelectorFactory.apply(options);
                return logicalFlowDecoratorDao.findByAppIdSelectorAndKind(selector, decoratorEntityKind);
            default:
                throw new UnsupportedOperationException("Cannot find decorators for selector kind: " + options.entityReference().kind());
        }
    }


    /**
     * Find decorators by selector.
     * @param options
     * @return
     */
    public Collection<LogicalFlowDecorator> findBySelector(IdSelectionOptions options) {
        switch (options.entityReference().kind()) {
            case APP_GROUP:
            case MEASURABLE:
            case ORG_UNIT:
            case PERSON:
            case PROCESS:
                return findByAppIdSelector(options);
            case DATA_TYPE:
                return findByDataTypeIdSelector(options);
            default:
                throw new UnsupportedOperationException("Cannot find decorators for selector kind: "+ options.entityReference().kind());
        }
    }


    // --- UPDATERS ---
    @Deprecated
    // Replace with a method that delete for a single flow id
    public int deleteAllDecoratorsForFlowIds(List<Long> flowIds) {
        return logicalFlowDecoratorDao.removeAllDecoratorsForFlowIds(flowIds);
    }


    public int[] deleteDecorators(long flowId,
                                  Collection<EntityReference> decoratorReferences,
                                  String username) {
        checkNotNull(decoratorReferences, "decoratorReferences cannot be null");
        LogicalFlow flow = logicalFlowDao.findByFlowId(flowId);
        int[] deleted = logicalFlowDecoratorDao.deleteDecorators(flowId, decoratorReferences);
        dataTypeUsageService.recalculateForApplications(flow.source(), flow.target());
        audit("Removed", decoratorReferences, flow, username);
        return deleted;
    }


    public int[] addDecorators(long flowId,
                               Set<EntityReference> decoratorReferences,
                               String username) {
        checkNotNull(decoratorReferences, "decoratorReferences cannot be null");
        if (decoratorReferences.isEmpty()) return new int[0];

        Collection<LogicalFlowDecorator> unrated = map(
                decoratorReferences,
                ref -> ImmutableLogicalFlowDecorator.builder()
                        .rating(AuthoritativenessRating.NO_OPINION)
                        .provenance("waltz")
                        .dataFlowId(flowId)
                        .decoratorEntity(ref)
                        .build());

        Collection<LogicalFlowDecorator> ratedDecorators = ratingsService
                .calculateRatings(unrated);

        int[] added = logicalFlowDecoratorDao.addDecorators(ratedDecorators);
        LogicalFlow flow = logicalFlowDao.findByFlowId(flowId);
        dataTypeUsageService.recalculateForApplications(flow.source(), flow.target());
        audit("Added", decoratorReferences, flow, username);

        return added;
    }


    public List<DecoratorRatingSummary> summarizeForSelector(IdSelectionOptions options) {
        checkNotNull(options, "options cannot be null");
        Select<Record1<Long>> selector = applicationIdSelectorFactory.apply(options);
        return logicalFlowDecoratorDao.summarizeForSelector(selector);
    }


    // --- HELPERS ---

    private Collection<LogicalFlowDecorator> findByDataTypeIdSelector(IdSelectionOptions options) {
        checkNotNull(options, "options cannot be null");
        Select<Record1<Long>> selector = dataTypeIdSelectorFactory.apply(options);
        return logicalFlowDecoratorDao.findByDecoratorEntityIdSelectorAndKind(selector, DATA_TYPE);
    }


    private Collection<LogicalFlowDecorator> findByAppIdSelector(IdSelectionOptions options) {
        checkNotNull(options, "options cannot be null");
        Select<Record1<Long>> selector = applicationIdSelectorFactory.apply(options);
        return logicalFlowDecoratorDao.findByAppIdSelector(selector);
    }


    private void audit(String verb,
                       Collection<EntityReference> decorators,
                       LogicalFlow flow,
                       String username) {

        ImmutableChangeLog logEntry = ImmutableChangeLog.builder()
                .parentReference(flow.source())
                .userId(username)
                .severity(Severity.INFORMATION)
                .message(String.format(
                        "%s characteristics: %s, for flow between %s and %s",
                        verb,
                        decorators.toString(),
                        flow.source().name().orElse(Long.toString(flow.source().id())),
                        flow.target().name().orElse(Long.toString(flow.target().id()))))
                .childKind(EntityKind.LOGICAL_DATA_FLOW)
                .operation(Operation.UPDATE)
                .build();

        changeLogService.write(logEntry);
        changeLogService.write(logEntry.withParentReference(flow.target()));

    }

}