/*
 * Copyright (C) 2016 AriaLyy(https://github.com/AriaLyy/Aria)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arialyy.aria.core.common;

import android.content.Context;
import android.util.SparseArray;
import com.arialyy.aria.core.AriaManager;
import com.arialyy.aria.core.download.DownloadEntity;
import com.arialyy.aria.core.download.DownloadTaskEntity;
import com.arialyy.aria.core.inf.AbsNormalEntity;
import com.arialyy.aria.core.inf.AbsTaskEntity;
import com.arialyy.aria.core.inf.IDownloadListener;
import com.arialyy.aria.core.inf.IEventListener;
import com.arialyy.aria.core.upload.UploadEntity;
import com.arialyy.aria.core.upload.UploadTaskEntity;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.CommonUtil;
import java.io.File;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by AriaL on 2017/7/1.
 * 任务处理器
 */
public abstract class AbsFileer<ENTITY extends AbsNormalEntity, TASK_ENTITY extends AbsTaskEntity<ENTITY>>
    implements Runnable, IUtil {
  public static final String STATE = "_state_";
  public static final String RECORD = "_record_";
  protected static final long SUB_LEN = 1024 * 1024;

  private final String TAG = "AbsFileer";
  protected IEventListener mListener;
  protected TASK_ENTITY mTaskEntity;
  protected ENTITY mEntity;
  protected File mConfigFile;//信息配置文件
  protected Context mContext;
  protected File mTempFile; //文件
  protected boolean isNewTask = true;
  protected StateConstance mConstance;
  private ExecutorService mFixedThreadPool;
  //总线程数
  private int mTotalThreadNum;
  //启动线程数
  private int mStartThreadNum;
  //已完成的线程数
  private int mCompleteThreadNum;
  private SparseArray<AbsThreadTask> mTask = new SparseArray<>();

  /**
   * 小于1m的文件不启用多线程
   */
  private Timer mTimer;
  private long mUpdateInterval = 1000;

  protected AbsFileer(IEventListener listener, TASK_ENTITY taskEntity) {
    mListener = listener;
    mTaskEntity = taskEntity;
    mEntity = mTaskEntity.getEntity();
    mContext = AriaManager.APP;
    mConstance = new StateConstance();
  }

  public void setNewTask(boolean newTask) {
    mTaskEntity.setNewTask(newTask);
  }

  @Override public void setMaxSpeed(double maxSpeed) {
    for (int i = 0; i < mTotalThreadNum; i++) {
      AbsThreadTask task = mTask.get(i);
      if (task != null) {
        task.setMaxSpeed(maxSpeed);
      }
    }
  }

  @Override public void run() {
    if (mConstance.isRunning) {
      return;
    }
    startFlow();
  }

  /**
   * 开始流程
   */
  private void startFlow() {
    mConstance.resetState();
    checkTask();
    if (mListener instanceof IDownloadListener) {
      ((IDownloadListener) mListener).onPostPre(mEntity.getFileSize());
    }
    if (!mTaskEntity.isSupportBP()) {
      mTotalThreadNum = 1;
      mStartThreadNum = 1;
      mConstance.THREAD_NUM = mTotalThreadNum;
      handleNoSupportBP();
    } else {
      mTotalThreadNum = isNewTask ? (mStartThreadNum = setNewTaskThreadNum()) : mStartThreadNum;
      mConstance.THREAD_NUM = mTotalThreadNum;
      handleBreakpoint();
    }
    startTimer();
  }

  /**
   * 设置新任务的最大线程数
   */
  protected abstract int setNewTaskThreadNum();

  /**
   * 启动进度获取定时器
   */
  private void startTimer() {
    mTimer = new Timer(true);
    mTimer.schedule(new TimerTask() {
      @Override public void run() {
        if (mConstance.isComplete()
            || mConstance.isStop()
            || mConstance.isCancel()
            || !mConstance.isRunning) {
          closeTimer();
        } else if (mConstance.CURRENT_LOCATION >= 0) {
          mListener.onProgress(mConstance.CURRENT_LOCATION);
        }
      }
    }, 0, mUpdateInterval);
  }

  protected void closeTimer() {
    if (mTimer != null) {
      mTimer.purge();
      mTimer.cancel();
      mTimer = null;
    }
  }

  /**
   * 设置定时器更新间隔
   *
   * @param interval 单位毫秒，不能小于0
   */
  protected void setUpdateInterval(long interval) {
    if (interval < 0) {
      ALog.w(TAG, "更新间隔不能小于0，默认为1000毫秒");
      return;
    }
    mUpdateInterval = interval;
  }

  @Override public long getFileSize() {
    return mEntity.getFileSize();
  }

  /**
   * 获取当前下载位置
   */
  @Override public long getCurrentLocation() {
    return mConstance.CURRENT_LOCATION;
  }

  @Override public boolean isRunning() {
    return mConstance.isRunning;
  }

  @Override public void cancel() {
    closeTimer();
    mConstance.isRunning = false;
    mConstance.isCancel = true;
    if (mFixedThreadPool != null) {
      mFixedThreadPool.shutdown();
    }
    for (int i = 0; i < mStartThreadNum; i++) {
      AbsThreadTask task = mTask.get(i);
      if (task != null) {
        task.cancel();
      }
    }
    if (mTaskEntity instanceof DownloadTaskEntity) {
      CommonUtil.delDownloadTaskConfig(mTaskEntity.isRemoveFile(),
          (DownloadEntity) mTaskEntity.getEntity());
    } else if (mTaskEntity instanceof UploadTaskEntity) {
      CommonUtil.delUploadTaskConfig(mTaskEntity.isRemoveFile(),
          (UploadEntity) mTaskEntity.getEntity());
    }
  }

  @Override public void stop() {
    closeTimer();
    mConstance.isRunning = false;
    mConstance.isStop = true;
    if (mConstance.isComplete()) return;
    if (mFixedThreadPool != null) {
      mFixedThreadPool.shutdown();
    }
    for (int i = 0; i < mStartThreadNum; i++) {
      AbsThreadTask task = mTask.get(i);
      if (task != null) {
        task.stop();
      }
    }
  }

  /**
   * 直接调用的时候会自动启动线程执行
   */
  @Override public void start() {
    new Thread(this).start();
  }

  @Override public void resume() {
    start();
  }

  /**
   * 检查任务是否是新任务，新任务条件：
   * 1、文件不存在
   * 2、下载记录文件不存在
   * 3、下载记录文件缺失或不匹配
   * 4、数据库记录不存在
   * 5、不支持断点，则是新任务
   */
  protected abstract void checkTask();

  /**
   * 检查记录文件，如果是新任务返回{@code true}，否则返回{@code false}
   */
  protected boolean checkConfigFile() {
    mStartThreadNum = 0;
    mCompleteThreadNum = 0;
    Properties pro = CommonUtil.loadConfig(mConfigFile);
    if (pro.isEmpty()) {
      return true;
    }
    Set<Object> keys = pro.keySet();
    int num = 0;
    for (Object key : keys) {
      String str = String.valueOf(key);
      Object state = pro.getProperty(str);
      if (str.contains(RECORD)) {
        num++;
      } else if (state != null && str.contains(STATE) && Integer.parseInt(state + "") == 1) {
        int i = Integer.parseInt(str.substring(str.length() - 1, str.length()));
        if (pro.getProperty(mTempFile.getName() + RECORD + i) != null) {
          mCompleteThreadNum++;
        }
      }
    }
    if (num == 0) {
      return true;
    }
    mStartThreadNum = num;
    for (int i = 0; i < mStartThreadNum; i++) {
      if (pro.getProperty(mTempFile.getName() + RECORD + i) == null) {
        Object state = pro.getProperty(mTempFile.getName() + STATE + i);
        if (state != null && Integer.parseInt(state + "") == 1) {
          continue;
        }
        return true;
      }
    }
    return false;
  }

  /**
   * 恢复记录地址
   *
   * @return {@code true}任务已完成
   */
  private boolean resumeRecordLocation(int i, long startL, long endL) {
    mConstance.CURRENT_LOCATION += endL - startL;
    ALog.d(TAG, "任务【" + mTaskEntity.getEntity().getFileName() + "】线程__" + i + "__已完成");
    mConstance.COMPLETE_THREAD_NUM = mCompleteThreadNum;
    mConstance.STOP_NUM++;
    mConstance.CANCEL_NUM++;
    if (mConstance.isComplete()) {
      if (mConfigFile.exists()) {
        mConfigFile.delete();
      }
      mListener.onComplete();
      mConstance.isRunning = false;
      return true;
    }
    return false;
  }

  /**
   * 启动断点任务时，创建单线程任务
   *
   * @param i 线程id
   * @param startL 该任务起始位置
   * @param endL 该任务结束位置
   * @param fileLength 该任务需要处理的文件长度
   */
  private AbsThreadTask createSingThreadTask(int i, long startL, long endL, long fileLength) {
    SubThreadConfig<TASK_ENTITY> config = new SubThreadConfig<>();
    config.FILE_SIZE = fileLength;
    config.URL = mEntity.isRedirect() ? mEntity.getRedirectUrl() : mEntity.getUrl();
    config.TEMP_FILE = mTempFile;
    config.THREAD_ID = i;
    config.START_LOCATION = startL;
    config.END_LOCATION = endL;
    config.CONFIG_FILE_PATH = mConfigFile.getPath();
    config.SUPPORT_BP = mTaskEntity.isSupportBP();
    config.TASK_ENTITY = mTaskEntity;
    return selectThreadTask(config);
  }

  /**
   * 处理断点
   */
  private void handleBreakpoint() {
    long fileLength = mEntity.getFileSize();
    Properties pro = CommonUtil.loadConfig(mConfigFile);
    long blockSize = fileLength / mTotalThreadNum;
    int[] recordL = new int[mTotalThreadNum];
    for (int i = 0; i < mTotalThreadNum; i++) {
      recordL[i] = -1;
    }
    int rl = 0;
    if (isNewTask && !handleNewTask()) {
      return;
    }
    for (int i = 0; i < mTotalThreadNum; i++) {
      long startL = i * blockSize, endL = (i + 1) * blockSize;
      Object state = pro.getProperty(mTempFile.getName() + STATE + i);
      Object record = pro.getProperty(mTempFile.getName() + RECORD + i);
      if (state != null && Integer.parseInt(state + "") == 1 && record != null) {  //该线程已经完成
        if (resumeRecordLocation(i, startL, endL)) return;
        continue;
      }

      //如果有记录，则恢复下载
      if (!isNewTask && record != null && Long.parseLong(record + "") >= 0) {
        Long r = Long.parseLong(record + "");
        //记录的位置需要在线程区间中
        if (startL < r && r < (i == (mTotalThreadNum - 1) ? fileLength : endL)) {
          mConstance.CURRENT_LOCATION += r - startL;
          startL = r;
        }
        ALog.d(TAG, "任务【" + mEntity.getFileName() + "】线程__" + i + "__恢复下载");
        recordL[rl] = i;
        rl++;
      } else {
        recordL[rl] = i;
        rl++;
      }
      if (i == (mTotalThreadNum - 1)) {
        //最后一个线程的结束位置即为文件的总长度
        endL = fileLength;
      }
      AbsThreadTask task = createSingThreadTask(i, startL, endL, fileLength);
      if (task == null) return;
      mTask.put(i, task);
    }
    startSingleTask(recordL);
  }

  /**
   * 启动单线程下载任务
   */
  private void startSingleTask(int[] recordL) {
    if (mConstance.CURRENT_LOCATION > 0) {
      mListener.onResume(mConstance.CURRENT_LOCATION);
    } else {
      mListener.onStart(mConstance.CURRENT_LOCATION);
    }
    mFixedThreadPool = Executors.newFixedThreadPool(recordL.length);
    for (int l : recordL) {
      if (l == -1) continue;
      Runnable task = mTask.get(l);
      if (task != null) {
        mFixedThreadPool.execute(task);
      }
    }
    mTaskEntity.setNewTask(false);
  }

  /**
   * 处理新任务
   *
   * @return {@code true}创建新任务失败
   */
  protected abstract boolean handleNewTask();

  /**
   * 处理不支持断点的下载
   */
  private void handleNoSupportBP() {
    SubThreadConfig<TASK_ENTITY> config = new SubThreadConfig<>();
    config.FILE_SIZE = mEntity.getFileSize();
    config.URL = mEntity.isRedirect() ? mEntity.getRedirectUrl() : mEntity.getUrl();
    config.TEMP_FILE = mTempFile;
    config.THREAD_ID = 0;
    config.START_LOCATION = 0;
    config.END_LOCATION = config.FILE_SIZE;
    config.CONFIG_FILE_PATH = mConfigFile.getPath();
    config.SUPPORT_BP = mTaskEntity.isSupportBP();
    config.TASK_ENTITY = mTaskEntity;
    AbsThreadTask task = selectThreadTask(config);
    if (task == null) return;
    mTask.put(0, task);
    mFixedThreadPool = Executors.newFixedThreadPool(1);
    mFixedThreadPool.execute(task);
    mListener.onStart(0);
  }

  /**
   * 选择单任务线程的类型
   */
  protected abstract AbsThreadTask selectThreadTask(SubThreadConfig<TASK_ENTITY> config);
}
