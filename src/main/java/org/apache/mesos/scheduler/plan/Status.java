package org.apache.mesos.scheduler.plan;

/**
 * Status of an {@link Element}.
 */
public enum Status {
  ERROR, /** execution experienced an error. */
  WAITING, /** execution has been interrupted. */
  PENDING, /** execution is pending offers. */
  IN_PROGRESS, /** execution is in progress. */
  COMPLETE /** execution is complete. */
}
