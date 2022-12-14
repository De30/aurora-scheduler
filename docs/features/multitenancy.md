Multitenancy
============

Aurora is a multi-tenant system that can run jobs of multiple clients/tenants.
Going beyond the [resource isolation on an individual host](resource-isolation.md), it is
crucial to prevent those jobs from stepping on each others toes.


Job Namespaces
--------------

The namespace for jobs in Aurora follows a hierarchical structure. This is meant to make it easier
to differentiate between different jobs. A job key consists of four parts. The four parts are
`<cluster>/<role>/<environment>/<jobname>` in that order:

* Cluster refers to the name of a particular Aurora installation.
* Role names are user accounts.
* Environment names are namespaces.
* Jobname is the custom name of your job.

Role names correspond to user accounts. They are used for
[authentication](../operations/security.md), as the linux user used to run jobs, and for the
assignment of [quota](#preemption). If you don't know what accounts are available, contact your
sysadmin.

The environment component in the job key, serves as a namespace. The values for
environment are validated in the scheduler. By default allowing any of `devel`, `test`,
`production`, and any value matching the regular expression `staging[0-9]*`. This validation can be
changed to allow any arbitrary regular expression by setting the scheduler option `allowed_job_environments`.

None of the values imply any difference in the scheduling behavior. Conventionally, the
"environment" is set so as to indicate a certain level of stability in the behavior of the job
by ensuring that an appropriate level of testing has been performed on the application code. e.g.
in the case of a typical Job, releases may progress through the following phases in order of
increasing level of stability: `devel`, `test`, `staging`, `production`.


Configuration Tiers
-------------------

Tier is a predefined bundle of task configuration options. Aurora schedules tasks and assigns them
resources based on their tier assignment. The default scheduler tier configuration allows for
3 tiers:

 - `revocable`: The `revocable` tier requires the task to run with [revocable](resource-isolation.md#oversubscription)
 resources.
 - `preemptible`: Setting the task???s tier to `preemptible` allows for the possibility of that task
 being [preempted](#preemption) by other tasks when cluster is running low on resources.
 - `preferred`: The `preferred` tier prevents the task from using [revocable](resource-isolation.md#oversubscription)
 resources and from being [preempted](#preemption).

Since it is possible that a cluster is configured with a custom tier configuration, users should
consult their cluster administrator to be informed of the tiers supported by the cluster. Attempts
to schedule jobs with an unsupported tier will be rejected by the scheduler.


Preemption
----------

In order to guarantee that important production jobs are always running, Aurora supports
preemption.

Let's consider we have a pending job that is candidate for scheduling but resource shortage pressure
prevents this. Active tasks can become the victim of preemption, if:

 - both candidate and victim are owned by the same role and the
   [priority](../reference/configuration.md#job-objects) of a victim is lower than the
   [priority](../reference/configuration.md#job-objects) of the candidate.
 - OR a victim is a `preemptible` or `revocable` [tier](#configuration-tiers) task and the candidate
   is a `preferred` [tier](#configuration-tiers) task.

In other words, tasks from `preferred` [tier](../reference/configuration.md#job-objects) jobs may
preempt tasks from any `preemptible` or `revocable` job. However, a `preferred` task may only be
preempted by tasks from `preferred` jobs in the same role with higher [priority](../reference/configuration.md#job-objects).

Aurora requires resource quotas for [production non-dedicated jobs](../reference/configuration.md#job-objects).
Quota is enforced at the job role level and when set, defines a non-preemptible pool of compute resources within
that role. All job types (service, adhoc or cron) require role resource quota unless a job has
[dedicated constraint set](constraints.md#dedicated-attribute).

To grant quota to a particular role in production, an operator can use the command
`aurora_admin set_quota`.
