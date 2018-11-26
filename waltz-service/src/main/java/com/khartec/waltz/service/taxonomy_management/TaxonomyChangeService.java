package com.khartec.waltz.service.taxonomy_management;

import com.khartec.waltz.model.EntityReference;
import com.khartec.waltz.model.taxonomy_management.*;
import org.jooq.lambda.tuple.Tuple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.khartec.waltz.common.Checks.checkNotNull;
import static com.khartec.waltz.common.MapUtilities.indexBy;
import static java.util.stream.Collectors.toMap;
import static org.jooq.lambda.tuple.Tuple.tuple;

@Service
public class TaxonomyChangeService {

    private final Map<TaxonomyChangeType, TaxonomyCommandProcessor> processorsByType;

    // BEGIN: hack
    private final Map<Long, TaxonomyChangeCommand> pendingCommandsHack = new HashMap<>();
    private AtomicLong commandCtrHack = new AtomicLong();
    // END: hack


    @Autowired
    public TaxonomyChangeService(
            List<TaxonomyCommandProcessor> processors) {
        processorsByType = processors
                .stream()
                .flatMap(p -> p.supportedTypes()
                        .stream()
                        .map(st -> tuple(st, p)))
                .collect(toMap(t -> t.v1, t -> t.v2));
    }


    public TaxonomyChangePreview preview(TaxonomyChangeCommand command) {
        TaxonomyCommandProcessor processor = getCommandProcessor(command);
        return processor.preview(command);
    }


    public TaxonomyChangePreview previewById(long id) {
        TaxonomyChangeCommand command = pendingCommandsHack.get(id);
        return preview(command);
    }


    public TaxonomyChangeCommand apply(TaxonomyChangeCommand command, String userId) {
      TaxonomyCommandProcessor processor = getCommandProcessor(command);
      return processor.apply(command, userId);
    }


    public TaxonomyChangeCommand submitPendingChange(TaxonomyChangeCommand cmd) {
        TaxonomyChangeCommand pendingCmd = ImmutableTaxonomyChangeCommand
                .copyOf(cmd)
                .withId(commandCtrHack.getAndIncrement());

        pendingCommandsHack.put(pendingCmd.id().get(), pendingCmd);
        return pendingCmd;
    }


    public Collection<TaxonomyChangeCommand> findPendingChangesByDomain(EntityReference domain) {
        return pendingCommandsHack
                .values()
                .stream()
                .filter(c -> c.changeDomain().equals(domain))
                .filter(c -> c.status() == TaxonomyChangeLifecycleStatus.DRAFT)
                .collect(Collectors.toList());
    }


    public TaxonomyChangeCommand applyById(long id, String userId) {
        TaxonomyChangeCommand command = pendingCommandsHack.get(id);
        TaxonomyChangeCommand updatedCommand = apply(command, userId);
        pendingCommandsHack.put(id, updatedCommand);
        return updatedCommand;
    }


    private TaxonomyCommandProcessor getCommandProcessor(TaxonomyChangeCommand command) {
        TaxonomyCommandProcessor processor = processorsByType.get(command.changeType());
        checkNotNull(processor, "Cannot find processor for type: %s", command.changeType());
        return processor;
    }


    public boolean removeById(long id) {
        return pendingCommandsHack.remove(id) != null;
    }
}