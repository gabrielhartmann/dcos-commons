package org.apache.mesos.scheduler.plan;

import com.google.inject.Inject;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.offer.*;
import org.apache.mesos.scheduler.TaskKiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Default deployment scheduler. See docs in {@link PlanScheduler} interface.
 */
public class DefaultPlanScheduler implements PlanScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPlanScheduler.class);

    private final OfferAccepter offerAccepter;
    private final OfferEvaluator offerEvaluator;
    private final TaskKiller taskKiller;

    @Inject
    public DefaultPlanScheduler(OfferAccepter offerAccepter, OfferEvaluator offerEvaluator, TaskKiller taskKiller) {
        this.offerAccepter = offerAccepter;
        this.offerEvaluator = offerEvaluator;
        this.taskKiller = taskKiller;
    }

    @Override
    public Collection<OfferID> resourceOffers(
            final SchedulerDriver driver,
            final List<Offer> offers,
            final Collection<? extends Block> blocks) {

        if (driver == null) {
            logger.error("driver was null");
            return Collections.emptyList();
        } else if (offers == null) {
            logger.error("offers was null");
            return Collections.emptyList();
        } else if (blocks == null) {
            logger.error("blocks was null");
            return Collections.emptyList();
        }

        List<OfferID> acceptedOfferIds = new ArrayList<>();
        List<Offer> availableOffers = new ArrayList<>(offers);

        for (Block block : blocks) {
            acceptedOfferIds.addAll(resourceOffers(driver, availableOffers, block));
            availableOffers = PlanUtils.filterAcceptedOffers(availableOffers, acceptedOfferIds);
        }

        return  acceptedOfferIds;
    }

    private Collection<OfferID> resourceOffers(
            SchedulerDriver driver,
            List<Offer> offers,
            Block block) {

        List<OfferID> acceptedOffers = new ArrayList<>();

        if (driver == null || offers == null) {
            logger.error("Unexpected null argument encountered: driver='{}' offers='{}'", driver, offers);
            return acceptedOffers;
        }

        if (block == null) {
            logger.info("Ignoring resource offers for null block.");
            return acceptedOffers;
        }

        if (!block.isPending()) {
            logger.info("Ignoring resource offers for block: {} status: {}", block.getName(), block.getStatus());
            return acceptedOffers;
        }

        logger.info("Processing resource offers for block: {}", block.getName());
        Optional<OfferRequirement> offerRequirementOptional = block.start();
        if (!offerRequirementOptional.isPresent()) {
            logger.info("No OfferRequirement for block: {}", block.getName());
            block.updateOfferStatus(Collections.emptyList());
            return acceptedOffers;
        }

        OfferRequirement offerRequirement = offerRequirementOptional.get();
        // It is harmless to attempt to kill tasks which have never been launched.  This call attempts to Kill all Tasks
        // with a Task name which is equivalent to that expressed by the OfferRequirement.  If no such Task is currently
        // running no operation occurs.
        killTasks(offerRequirement);

        // Block has returned an OfferRequirement to process. Find offers which match the
        // requirement and accept them, if any are found:
        List<OfferRecommendation> recommendations = offerEvaluator.evaluate(offerRequirement, offers);
        if (recommendations.isEmpty()) {
            // Log that we're not finding suitable offers, possibly due to insufficient resources.
            logger.warn(
                    "Unable to find any offers which fulfill requirement provided by block {}: {}",
                    block.getName(), offerRequirement);
            block.updateOfferStatus(Collections.emptyList());
            return acceptedOffers;
        }

        acceptedOffers = offerAccepter.accept(driver, recommendations);
        // Notify block of offer outcome:
        if (acceptedOffers.size() > 0) {
            block.updateOfferStatus(getOperations(recommendations));
        } else {
            // If no Operations occurred it may be of interest to the Block.  For example it may want to set it's state
            // to Pending to ensure it will be reattempted on the next Offer cycle.
            block.updateOfferStatus(Collections.emptyList());
        }

        return acceptedOffers;
    }

    private void killTasks(OfferRequirement offerRequirement) {
        for (TaskRequirement taskRequirement : offerRequirement.getTaskRequirements()) {
            String taskName = taskRequirement.getTaskInfo().getName();
            taskKiller.killTask(taskName, false);
        }
    }

    private Collection<Offer.Operation> getOperations(Collection<OfferRecommendation> recommendations) {
        return recommendations.stream()
                .map(OfferRecommendation::getOperation)
                .collect(Collectors.toList());
    }
}
