/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.ams.server.service.impl;

import com.netease.arctic.ams.api.ErrorMessage;
import com.netease.arctic.ams.api.InvalidObjectException;
import com.netease.arctic.ams.api.JobId;
import com.netease.arctic.ams.api.MetaException;
import com.netease.arctic.ams.api.NoSuchObjectException;
import com.netease.arctic.ams.api.OptimizeStatus;
import com.netease.arctic.ams.api.OptimizeTask;
import com.netease.arctic.ams.api.OptimizeType;
import com.netease.arctic.ams.api.properties.OptimizeTaskProperties;
import com.netease.arctic.ams.server.mapper.ContainerMetadataMapper;
import com.netease.arctic.ams.server.mapper.OptimizeQueueMapper;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.Container;
import com.netease.arctic.ams.server.model.OptimizeQueueItem;
import com.netease.arctic.ams.server.model.OptimizeQueueMeta;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.model.TableQuotaInfo;
import com.netease.arctic.ams.server.model.TableTaskHistory;
import com.netease.arctic.ams.server.optimize.BaseIcebergOptimizePlan;
import com.netease.arctic.ams.server.optimize.BaseOptimizePlan;
import com.netease.arctic.ams.server.optimize.OptimizeTaskItem;
import com.netease.arctic.ams.server.optimize.TableOptimizeItem;
import com.netease.arctic.ams.server.service.IJDBCService;
import com.netease.arctic.ams.server.service.ITableTaskHistoryService;
import com.netease.arctic.ams.server.service.ServiceContainer;
import com.netease.arctic.ams.server.utils.OptimizeStatusUtil;
import com.netease.arctic.hive.table.SupportHive;
import com.netease.arctic.hive.utils.HiveTableUtil;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.utils.CompatiblePropertyUtil;
import com.netease.arctic.utils.TableTypeUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.ibatis.session.SqlSession;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class OptimizeQueueService extends IJDBCService {
  private static final Logger LOG = LoggerFactory.getLogger(OptimizeQueueService.class);

  private final Map<Integer, OptimizeQueueWrapper> optimizeQueues = new HashMap<>();

  private final ReentrantLock queueOperateLock = new ReentrantLock();

  private static final int MAX_POOL_TASK_CNT = 50;

  public OptimizeQueueService() {
    init();
  }

  public void init() {
    LOG.info("OptimizeQueueManager init");
    loadOptimizeQueues();
    LOG.info("OptimizeQueueManager init completed");
  }

  public int getQueueId(Map<String, String> properties) throws InvalidObjectException {
    String groupName = CompatiblePropertyUtil.propertyAsString(properties,
        TableProperties.SELF_OPTIMIZING_GROUP, TableProperties.SELF_OPTIMIZING_GROUP_DEFAULT);
    return getOptimizeQueue(groupName).getOptimizeQueueMeta().getQueueId();
  }

  /*
   * queue operation
   */

  /**
   * Loading queues from the system database
   */
  public void loadOptimizeQueues() {
    queueOperateLock.lock();
    try (SqlSession sqlSession = getSqlSession(true)) {
      OptimizeQueueMapper optimizeQueueMapper = getMapper(sqlSession, OptimizeQueueMapper.class);
      List<OptimizeQueueMeta> optimizeQueues = optimizeQueueMapper.selectOptimizeQueues();
      optimizeQueues
          .forEach(q -> this.optimizeQueues.put(q.getQueueId(), OptimizeQueueWrapper.build(q)));
    } finally {
      queueOperateLock.unlock();
    }
  }

  /**
   * Add optimize queue
   *
   * @param queue OptimizeQueueMeta
   */
  public OptimizeQueueMeta createQueue(OptimizeQueueMeta queue) throws MetaException {
    queueOperateLock.lock();

    try (SqlSession sqlSession = getSqlSession(true)) {
      validateAddQueue(queue);
      OptimizeQueueMapper optimizeQueueMapper = getMapper(sqlSession, OptimizeQueueMapper.class);
      optimizeQueueMapper.insertQueue(queue);
      OptimizeQueueMeta finalQueue = optimizeQueueMapper.selectOptimizeQueue(queue.getName());
      optimizeQueues.put(finalQueue.getQueueId(), OptimizeQueueWrapper.build(finalQueue));

      return queue;
    } finally {
      queueOperateLock.unlock();
    }
  }

  /**
   * Update optimize queue
   *
   * @param queue new OptimizeQueueMeta
   */
  public void updateQueue(OptimizeQueueMeta queue)
      throws MetaException, InvalidObjectException {
    queueOperateLock.lock();
    try {
      validateAddQueue(queue);
      OptimizeQueueWrapper optimizeQueueWrapper = getQueue(queue.getQueueId());
      optimizeQueueWrapper.lock();

      try (SqlSession sqlSession = getSqlSession(true)) {
        validateUpdateQueue(optimizeQueueWrapper, queue);

        OptimizeQueueMapper optimizeQueueMapper = getMapper(sqlSession, OptimizeQueueMapper.class);
        optimizeQueueMapper.updateQueue(queue);
        optimizeQueueWrapper.getOptimizeQueueItem();
      } finally {
        optimizeQueueWrapper.unlock();
      }
    } finally {
      queueOperateLock.unlock();
    }
  }

  /**
   * Delete optimize queue
   *
   * @param queueId queueId
   * @throws NoSuchObjectException when queue is not empty
   */
  public void removeQueue(int queueId) throws NoSuchObjectException, InvalidObjectException {
    OptimizeQueueWrapper optimizeQueueWrapper = getQueue(queueId);
    queueOperateLock.lock();
    try {
      optimizeQueueWrapper.lock();
      try (SqlSession sqlSession = getSqlSession(true)) {
        validateRemoveQueue(optimizeQueueWrapper);

        OptimizeQueueMapper optimizeQueueMapper = getMapper(sqlSession, OptimizeQueueMapper.class);
        optimizeQueueMapper.deleteQueue(queueId);

        optimizeQueues.remove(queueId);
      } finally {
        optimizeQueueWrapper.unlock();
      }
    } finally {
      queueOperateLock.unlock();
    }
  }

  /**
   * delete all OptimizeQueue
   */
  public void removeAllQueue() {
    try (SqlSession sqlSession = getSqlSession(true)) {
      OptimizeQueueMapper optimizeQueueMapper = getMapper(sqlSession, OptimizeQueueMapper.class);
      optimizeQueueMapper.deleteAllQueue();
    }
  }

  /**
   * Get optimize queue.
   *
   * @param queueId queueId
   * @return OptimizeQueueItem
   * @throws InvalidObjectException when can't find queue
   */
  public OptimizeQueueItem getOptimizeQueue(int queueId) throws InvalidObjectException {
    return getQueue(queueId).getOptimizeQueueItem();
  }

  /**
   * Get optimize queue.
   *
   * @param queueName queueName
   * @return OptimizeQueueItem
   * @throws InvalidObjectException when can't find queue
   */
  public OptimizeQueueItem getOptimizeQueue(String queueName) throws InvalidObjectException {
    Preconditions.checkNotNull(queueName, "queueName can't be null");
    return getQueue(queueName).getOptimizeQueueItem();
  }

  /**
   * Get tables of optimize queue.
   * @param queueName queueName
   * @return set of tables
   * @throws InvalidObjectException when can't find queue
   */
  public Set<TableIdentifier> getTablesOfQueue(String queueName) throws InvalidObjectException {
    return getQueue(queueName).getTables();
  }

  /**
   * add task
   *
   * @param task OptimizeTaskItem
   * @throws NoSuchObjectException when queue is lost
   */
  public void submitTask(OptimizeTaskItem task) throws NoSuchObjectException, InvalidObjectException {
    Objects.requireNonNull(task, "taskItem can't be null");
    Objects.requireNonNull(task.getOptimizeTask(), "task cant' be null");
    getQueue(task.getOptimizeTask().getQueueId()).addIntoOptimizeQueue(task);
  }

  private OptimizeQueueWrapper getQueue(String queueName) throws InvalidObjectException {
    return optimizeQueues.values().stream()
        .filter(q -> queueName.equals(q.getOptimizeQueueItem().getOptimizeQueueMeta().getName()))
        .findFirst()
        .orElseThrow(() -> new InvalidObjectException("lost queue " + queueName));
  }

  private OptimizeQueueWrapper getQueue(int queueId) throws InvalidObjectException {
    OptimizeQueueWrapper optimizeQueueWrapper = optimizeQueues.get(queueId);
    if (optimizeQueueWrapper == null) {
      throw new InvalidObjectException("lost queue " + queueId);
    } else {
      return optimizeQueueWrapper;
    }
  }

  public List<OptimizeQueueMeta> getQueues() throws NoSuchObjectException {
    try (SqlSession sqlSession = getSqlSession(true)) {
      OptimizeQueueMapper optimizeQueueMapper = getMapper(sqlSession, OptimizeQueueMapper.class);
      return optimizeQueueMapper.selectOptimizeQueues();
    }
  }

  public OptimizeTask pollTask(int queueId, JobId jobId, String attemptId, long waitTime)
      throws NoSuchObjectException, TException {
    try {
      OptimizeTask task = getQueue(queueId).poll(jobId, attemptId, waitTime);
      if (task != null) {
        LOG.info("{} pollTask success, {}", jobId, task);
      } else {
        throw new NoSuchObjectException("no Optimize task in current queue: " + queueId);
      }
      return task;
    } catch (Throwable t) {
      if (!(t instanceof NoSuchObjectException)) {
        LOG.error("failed to poll task", t);
      }
      throw t;
    }
  }

  private void validateRemoveQueue(OptimizeQueueWrapper queue) throws InvalidObjectException {
    if (!queue.isEmpty()) {
      throw new InvalidObjectException(
          "not support remove queue now, queue is not empty, size = " + queue.size());
    }
  }

  private void validateUpdateQueue(OptimizeQueueWrapper queue, OptimizeQueueMeta newQueue)
      throws InvalidObjectException {
  }

  private void validateAddQueue(OptimizeQueueMeta queue) throws MetaException {
    Objects.requireNonNull(queue, "queue can't be null");
    if (queue.getName() == null) {
      throw new MetaException("queue name can't be null");
    }
    queue.setName(queue.getName().trim());
  }

  public void bind(TableIdentifier tableIdentifier, String queueName) throws InvalidObjectException {
    getQueue(queueName).bindTable(tableIdentifier);
  }

  public void release(TableIdentifier tableIdentifier) {
    // Delete from all queues because the queue in which the task is running may differ from
    // the parameters in arcticTable Properties
    optimizeQueues.values().forEach(c -> c.releaseTable(tableIdentifier));
  }

  public void clearTasks(TableIdentifier tableIdentifier) {
    // Delete from all queues because the queue in which the task is running may differ from
    // the parameters in arcticTable Properties
    optimizeQueues.values().forEach(c -> c.clearTasks(tableIdentifier));
  }

  public List<Container> getContainers() {
    try (SqlSession sqlSession = getSqlSession(true)) {
      ContainerMetadataMapper containerMetadataMapper = getMapper(sqlSession, ContainerMetadataMapper.class);
      return containerMetadataMapper.getContainers();
    }
  }

  public void insertContainer(Container container) {
    try (SqlSession sqlSession = getSqlSession(true)) {
      ContainerMetadataMapper containerMetadataMapper = getMapper(sqlSession, ContainerMetadataMapper.class);
      containerMetadataMapper.insertContainer(container);
    }
  }

  public static class OptimizeQueueWrapper {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition planThreadCondition = lock.newCondition();
    private final AtomicBoolean planThreadStarted = new AtomicBoolean(false);
    private final OptimizeQueueItem optimizeQueue;
    private final Queue<OptimizeTaskItem> tasks;
    private Set<TableIdentifier> tables = new HashSet<>();
    // plan retry times
    private final int retryTime = 5;
    // plan retry interval unit ms
    private final long retryInterval = 1000;

    private OptimizeQueueWrapper(OptimizeQueueMeta optimizeQueue) {
      this.optimizeQueue = new OptimizeQueueItem(optimizeQueue);
      this.tasks = new LinkedTransferQueue<>();
    }

    public static OptimizeQueueWrapper build(OptimizeQueueMeta optimizeQueue) {
      return new OptimizeQueueWrapper(optimizeQueue);
    }

    private void bindTable(TableIdentifier tableIdentifier) {
      lock();
      try {
        tables.add(tableIdentifier);
        LOG.info("operation queue success, bind {} with queue {}", tableIdentifier, queueName());
      } finally {
        unlock();
      }
    }

    private void releaseTable(TableIdentifier tableIdentifier) {
      lock();
      try {
        clearTasks(tableIdentifier);
        boolean removed = tables.remove(tableIdentifier);
        if (removed) {
          LOG.info("operation queue success, remove {} from queue {}", tableIdentifier, queueName());
        }
      } finally {
        unlock();
      }
    }

    private void addIntoOptimizeQueue(OptimizeTaskItem task) throws InvalidObjectException {
      lock();
      try {
        if (!tables.contains(task.getTableIdentifier())) {
          throw new InvalidObjectException(
              queueName() + " not allow task of table " + task.getTableIdentifier());
        }
        // clear useless files produced by failed full optimize task support hive
        if (task.getOptimizeRuntime().getStatus() == OptimizeStatus.Failed) {
          String subDirectory =
              task.getOptimizeTask().getProperties().get(OptimizeTaskProperties.CUSTOM_HIVE_SUB_DIRECTORY);

          if (StringUtils.isNotEmpty(subDirectory)) {
            try {
              ArcticTable arcticTable = ServiceContainer.getOptimizeService()
                  .getTableOptimizeItem(task.getTableIdentifier()).getArcticTable();

              String location;
              if (arcticTable.spec().isUnpartitioned()) {
                location = HiveTableUtil.newHiveDataLocation(((SupportHive) arcticTable).hiveLocation(),
                    arcticTable.spec(), null, subDirectory);
              } else {
                location = HiveTableUtil.newHiveDataLocation(((SupportHive) arcticTable).hiveLocation(),
                    arcticTable.spec(), DataFiles.data(arcticTable.spec(), task.getOptimizeTask().getPartition()),
                    subDirectory);
              }

              if (arcticTable.io().exists(location)) {
                for (FileStatus fileStatus : arcticTable.io().list(location)) {
                  String fileLocation = fileStatus.getPath().toUri().getPath();
                  // now file naming rule is nodeId-fileType-txId-partitionId-taskId-fileCount(%d-%s-%d-%05d-%d-%010d)
                  // for files produced by optimize, the taskId is attemptId
                  String pattern = ".*(\\d{5}-)" + task.getOptimizeRuntime().getAttemptId() + "(-\\d{10}).*";
                  if (Pattern.matches(pattern, fileLocation)) {
                    arcticTable.io().deleteFile(fileLocation);
                    LOG.debug("delete file {} by produced failed optimize task.", fileLocation);
                  }
                }
              }
            } catch (Exception e) {
              LOG.error("delete files failed", e);
              return;
            }
          }
        }

        if (tasks.offer(task)) {
          if (!OptimizeStatusUtil.in(task.getOptimizeStatus(), OptimizeStatus.Pending) ||
              task.getOptimizeTask().getQueueId() != optimizeQueue.getOptimizeQueueMeta()
                  .getQueueId()) {
            // update task status
            task.onPending();
          }
          LOG.info("submitTask into queue {} success, {}", queueName(), task);
        } else {
          throw new InvalidObjectException(
              queueName() + " is full, size = " + tasks.size());
        }
      } finally {
        unlock();
      }
    }

    public void lock() {
      this.lock.lock();
    }

    public void unlock() {
      this.lock.unlock();
    }

    public String queueName() {
      return optimizeQueue.getOptimizeQueueMeta().getName() + "-" +
          optimizeQueue.getOptimizeQueueMeta().getQueueId();
    }

    public OptimizeTask poll(JobId jobId, final String attemptId, long waitTime) {
      long startTime = System.currentTimeMillis();
      while (true) {
        long duration = System.currentTimeMillis() - startTime;
        if (duration > waitTime) {
          LOG.warn("pool task cost too much time {} ms, return null", duration);
          return null;
        }
        OptimizeTaskItem task = tasks.poll();
        if (task == null) {
          try {
            if (planThreadStarted.compareAndSet(false, true)) {
              Thread planThread = new Thread(() -> {
                int retry = 0;
                boolean isHaveTask = false;

                long threadStartTime = System.currentTimeMillis();
                try {
                  LOG.info("this plan started {}, {}", attemptId, jobId);
                  while (retry <= retryTime) {
                    LOG.debug("start get plan task retry {}", retry);
                    retry++;
                    long planStartTime = System.currentTimeMillis();
                    List<OptimizeTaskItem> tasks = plan(planStartTime);
                    if (CollectionUtils.isNotEmpty(tasks)) {
                      isHaveTask = true;
                      break;
                    }

                    try {
                      Thread.sleep(retryInterval);
                    } catch (InterruptedException e) {
                      LOG.error("Internal Thread Interrupted", e);
                    }
                  }

                  // no task have planned
                  if (!isHaveTask) {
                    LOG.debug("The queue {} has retry {} times, no task have planned",
                        optimizeQueue.getOptimizeQueueMeta().queueId,
                        retryTime);
                  }
                } catch (Throwable t) {
                  LOG.error("failed to plan", t);
                  throw t;
                } finally {
                  LOG.info("this plan end {}, cost {} ms, retry {}",
                      attemptId, System.currentTimeMillis() - threadStartTime, retry);
                  if (planThreadStarted.compareAndSet(true, false)) {
                    lock();
                    try {
                      planThreadCondition.signalAll();
                    } finally {
                      unlock();
                    }
                  }
                }
              });
              planThread.setName(
                  "Optimize Plan Thread Queue-" + optimizeQueue.getOptimizeQueueMeta().getQueueId());
              planThread.start();
            } else {
              lock();
              try {
                // if timeout, return null
                if (!planThreadCondition.await(waitTime - (System.currentTimeMillis() - startTime),
                    TimeUnit.MILLISECONDS)) {
                  LOG.debug("The queue {} has no task have planned", optimizeQueue.getOptimizeQueueMeta().getQueueId());
                  return null;
                }
              } finally {
                unlock();
              }
            }
          } catch (Exception e) {
            LOG.error("Failure when starting the plan thread, " + e);
          }
        } else {
          if (tables.contains(task.getTableIdentifier())) {
            try {
              // load files from sysdb
              task.setFiles();
            } catch (Exception e) {
              task.clearFiles();
              LOG.error("{} failed to load files from sysdb, try put task back into queue", task.getTaskId(), e);
              if (!tasks.offer(task)) {
                task.onFailed(new ErrorMessage(System.currentTimeMillis(), "failed to put task back into queue"), 0);
              }
            }
            // update max execute time
            task.setMaxExecuteTime();
            TableTaskHistory tableTaskHistory = task.onExecuting(jobId, attemptId);
            try {
              insertTableTaskHistory(tableTaskHistory);
            } catch (Exception e) {
              LOG.error("failed to insert tableTaskHistory, {} ignore", tableTaskHistory, e);
            }
            return task.getOptimizeTask();
          } else {
            LOG.warn("get task {} from queue {} but table {} not in this queue",
                task.getTaskId(), queueName(), task.getTableIdentifier());
          }
        }
      }
    }

    private void insertTableTaskHistory(TableTaskHistory tableTaskHistory) {
      ITableTaskHistoryService tableTaskHistoryService = ServiceContainer.getTableTaskHistoryService();
      tableTaskHistoryService.insertTaskHistory(tableTaskHistory);
    }

    private void clearTasks(TableIdentifier tableIdentifier) {
      lock();
      try {
        if (tables.contains(tableIdentifier)) {
          List<OptimizeTaskItem> tempTasks = new ArrayList<>();
          while (!tasks.isEmpty()) {
            OptimizeTaskItem task = tasks.poll();
            if (task != null && !Objects.equals(tableIdentifier, task.getTableIdentifier())) {
              tempTasks.add(task);
            }
          }
          tasks.addAll(tempTasks);
        }
      } finally {
        unlock();
      }
    }

    public boolean isEmpty() {
      return tasks.isEmpty();
    }

    public int size() {
      return tasks.size();
    }

    public Set<TableIdentifier> getTables() {
      return Sets.newHashSet(tables);
    }

    public OptimizeQueueItem getOptimizeQueueItem() {
      optimizeQueue.setSize(size());
      return optimizeQueue;
    }

    private List<OptimizeTaskItem> plan(long currentTime) {
      List<TableQuotaInfo> tableSort = sortTableByQuota(new ArrayList<>(tables));

      if (LOG.isDebugEnabled()) {
        LOG.debug("get sort table {}", tableSort);
      }
      for (TableQuotaInfo tableQuotaInfo : tableSort) {
        TableIdentifier tableIdentifier = tableQuotaInfo.getTableIdentifier();
        LOG.debug("{} try plan, quota {}", tableIdentifier, tableQuotaInfo.getQuota());
        try {
          TableOptimizeItem tableItem = ServiceContainer.getOptimizeService().getTableOptimizeItem(tableIdentifier);

          Map<String, String> properties = tableItem.getArcticTable(false).properties();
          int queueId = ServiceContainer.getOptimizeQueueService().getQueueId(properties);

          // queue was updated
          if (optimizeQueue.getOptimizeQueueMeta().getQueueId() != queueId) {
            releaseTable(tableIdentifier);
            ServiceContainer.getOptimizeQueueService().getQueue(queueId).bindTable(tableIdentifier);
            continue;
          }

          tableItem.checkTaskExecuteTimeout();
          // if enable_optimize is false
          if (!CompatiblePropertyUtil.propertyAsBoolean(tableItem.getArcticTable(false).properties(),
              TableProperties.ENABLE_SELF_OPTIMIZING,
              TableProperties.ENABLE_SELF_OPTIMIZING_DEFAULT)) {
            LOG.debug("{} is not enable optimize continue", tableIdentifier);
            continue;
          }

          if (tableItem.optimizeRunning()) {
            LOG.debug("{} is running continue", tableIdentifier);

            // add failed tasks and retry
            List<OptimizeTaskItem> toExecuteTasks = addTask(tableItem, Collections.emptyList());
            if (!toExecuteTasks.isEmpty()) {
              LOG.info("{} add {} failed tasks into queue and retry",
                  tableItem.getTableIdentifier(), toExecuteTasks.size());
              return toExecuteTasks;
            } else {
              continue;
            }
          }

          BaseOptimizePlan optimizePlan;
          List<BaseOptimizeTask> optimizeTasks;

          Map<String, Boolean> partitionIsRunning = tableItem.generatePartitionRunning();
          if (TableTypeUtil.isIcebergTableFormat(tableItem.getArcticTable(false))) {
            if (!BaseIcebergOptimizePlan.tableChanged(tableItem.getArcticTable(false),
                tableItem.getTableOptimizeRuntime())) {
              tableItem.persistTableOptimizeRuntime();
              LOG.debug("table {} not changed, no need plan", tableIdentifier);
              continue;
            }
            List<FileScanTask> fileScanTasks;
            try (CloseableIterable<FileScanTask> filesIterable = 
                     tableItem.getArcticTable(false).asUnkeyedTable().newScan().planFiles()) {
              fileScanTasks = Lists.newArrayList(filesIterable);
            } catch (IOException e) {
              throw new UncheckedIOException("Failed to close table scan of " + tableIdentifier, e);
            }

            optimizePlan = tableItem.getIcebergFullPlan(fileScanTasks, queueId, currentTime, partitionIsRunning);
            optimizeTasks = optimizePlan.plan();
            // if no major tasks, then plan minor tasks
            if (CollectionUtils.isEmpty(optimizeTasks)) {
              optimizePlan = tableItem.getIcebergMinorPlan(fileScanTasks, queueId, currentTime, partitionIsRunning);
              optimizeTasks = optimizePlan.plan();
            }
          } else {
            optimizePlan = tableItem.getFullPlan(queueId, currentTime, partitionIsRunning);
            optimizeTasks = optimizePlan.plan();

            // if no full tasks, then plan minor tasks
            if (CollectionUtils.isEmpty(optimizeTasks)) {
              optimizePlan = tableItem.getMajorPlan(queueId, currentTime, partitionIsRunning);
              optimizeTasks = optimizePlan.plan();
            }

            // if no major tasks and keyed table, then plan minor tasks
            if (tableItem.isKeyedTable() && CollectionUtils.isEmpty(optimizeTasks)) {
              optimizePlan = tableItem.getMinorPlan(queueId, currentTime, partitionIsRunning);
              optimizeTasks = optimizePlan.plan();
            }
          }

          initTableOptimizeRuntime(tableItem, optimizePlan, optimizeTasks, optimizePlan.getPartitionOptimizeType());
          LOG.debug("{} after plan get {} tasks", tableItem.getTableIdentifier(), optimizeTasks.size());

          List<OptimizeTaskItem> toExecuteTasks = addTask(tableItem, optimizeTasks);
          if (!toExecuteTasks.isEmpty()) {
            LOG.info("{} after plan put {} tasks into queue", tableItem.getTableIdentifier(), toExecuteTasks.size());
            return toExecuteTasks;
          } else {
            LOG.debug("{} after plan put no tasks into queue, try next table", tableItem.getTableIdentifier());
          }
        } catch (Throwable e) {
          LOG.error(tableIdentifier + " plan failed, continue", e);
        }
      }

      return Collections.emptyList();
    }

    private List<TableQuotaInfo> sortTableByQuota(List<TableIdentifier> tables) {
      long currentTime = System.currentTimeMillis();
      List<TableQuotaInfo> tableQuotaInfoList = tables.stream()
          .map(tableIdentifier -> {
            try {
              return new TableQuotaInfo(tableIdentifier, evalQuotaRate(tableIdentifier, currentTime),
                  ServiceContainer.getOptimizeService().getTableOptimizeItem(tableIdentifier).getQuotaCache());
            } catch (NoSuchObjectException e) {
              LOG.error("can't find table", e);
              return null;
            } catch (Throwable t) {
              LOG.error("unexpected error", t);
              return null;
            }
          }).filter(Objects::nonNull).collect(Collectors.toList());

      return tableQuotaInfoList.stream().sorted().collect(Collectors.toList());
    }

    private BigDecimal evalQuotaRate(TableIdentifier tableId, long currentTime) throws NoSuchObjectException {
      TableOptimizeItem tableItem;
      tableItem = ServiceContainer.getOptimizeService().getTableOptimizeItem(tableId);
      String latestTaskPlanGroup = tableItem.getTableOptimizeRuntime().getLatestTaskPlanGroup();
      if (StringUtils.isEmpty(latestTaskPlanGroup)) {
        return BigDecimal.ZERO;
      }

      List<TableTaskHistory> latestTaskHistories =
          ServiceContainer.getTableTaskHistoryService().selectTaskHistory(tableId, latestTaskPlanGroup);
      if (CollectionUtils.isEmpty(latestTaskHistories)) {
        return BigDecimal.ZERO;
      }

      long totalCostTime = 0;
      long latestStartTime = 0;
      for (TableTaskHistory latestTaskHistory : latestTaskHistories) {
        if (latestStartTime == 0 || latestStartTime > latestTaskHistory.getStartTime()) {
          latestStartTime = latestTaskHistory.getStartTime();
        }

        if (latestTaskHistory.getCostTime() != 0) {
          totalCostTime = totalCostTime + latestTaskHistory.getCostTime();
        } else {
          totalCostTime = totalCostTime + currentTime - latestTaskHistory.getStartTime();
        }
      }

      if (currentTime - latestStartTime == 0) {
        return BigDecimal.valueOf(Long.MAX_VALUE);
      }

      BigDecimal currentQuota = new BigDecimal(totalCostTime)
          .divide(new BigDecimal(currentTime - latestStartTime),
              2,
              RoundingMode.HALF_UP);

      BigDecimal tableQuota = BigDecimal.valueOf(tableItem.getQuotaCache());

      if (tableQuota.compareTo(BigDecimal.ZERO) <= 0) {
        return BigDecimal.valueOf(Long.MAX_VALUE);
      }

      return currentQuota.divide(tableQuota, 2, RoundingMode.HALF_UP);
    }

    private void initTableOptimizeRuntime(TableOptimizeItem tableItem,
                                          BaseOptimizePlan optimizePlan,
                                          List<BaseOptimizeTask> optimizeTasks,
                                          Map<String, OptimizeType> partitionOptimizeType) {
      if (CollectionUtils.isNotEmpty(optimizeTasks)) {
        TableOptimizeRuntime oldTableOptimizeRuntime = tableItem.getTableOptimizeRuntime().clone();
        try {
          // set latest optimize time
          for (String currentPartition : optimizePlan.getCurrentPartitions()) {
            if (partitionOptimizeType.get(currentPartition) != null) {
              switch (partitionOptimizeType.get(currentPartition)) {
                case Minor:
                  tableItem.getTableOptimizeRuntime().putLatestMinorOptimizeTime(currentPartition, -1);
                  break;
                case Major:
                  tableItem.getTableOptimizeRuntime().putLatestMajorOptimizeTime(currentPartition, -1);
                  break;
                case FullMajor:
                  tableItem.getTableOptimizeRuntime().putLatestFullOptimizeTime(currentPartition, -1);
                  break;
              }
            }
          }

          // set current snapshot id
          tableItem.getTableOptimizeRuntime().setCurrentSnapshotId(optimizePlan.getCurrentSnapshotId());
          if (tableItem.isKeyedTable()) {
            tableItem.getTableOptimizeRuntime().setCurrentChangeSnapshotId(optimizePlan.getCurrentSnapshotId());
          }

          tableItem.getTableOptimizeRuntime().setLatestTaskPlanGroup(optimizeTasks.get(0).getTaskPlanGroup());
          tableItem.persistTableOptimizeRuntime();
        } catch (Throwable e) {
          tableItem.getTableOptimizeRuntime().restoreTableOptimizeRuntime(oldTableOptimizeRuntime);
          throw e;
        }
      }
    }

    private List<OptimizeTaskItem> addTask(TableOptimizeItem tableItem, List<BaseOptimizeTask> optimizeTasks) {
      try {
        tableItem.addNewOptimizeTasks(optimizeTasks);
      } catch (Throwable t) {
        LOG.error("failed to add Optimize tasks[" + optimizeTasks.size() + "] for " +
            tableItem.getTableIdentifier(), t);
        return Collections.emptyList();
      }

      List<OptimizeTaskItem> toExecuteTasks = new ArrayList<>();
      try {
        List<OptimizeTaskItem> optimizeTasksToExecute = tableItem.getOptimizeTasksToExecute(MAX_POOL_TASK_CNT);
        for (OptimizeTaskItem optimizeTaskItem : optimizeTasksToExecute) {
          addIntoOptimizeQueue(optimizeTaskItem);
          toExecuteTasks.add(optimizeTaskItem);
        }
      } catch (InvalidObjectException e) {
        LOG.error(tableItem.getTableIdentifier() + " can't add Optimize task, give up planning next table", e);
        LOG.info("{} put {} tasks into queue", tableItem.getTableIdentifier(), toExecuteTasks.size());
        return toExecuteTasks;
      }

      return toExecuteTasks;
    }
  }
}
