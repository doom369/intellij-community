/*
 * Copyright 2000-2005 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.compiler;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.DataInputStream;

/**
 * A simple implementation of ValidityState that is enough for most cases.
 * The file is considered modified if its timeastamp is changed.
 */
public final class TimestampValidityState implements ValidityState {
  private final long myTimestamp;

  public TimestampValidityState(long timestamp) {
    myTimestamp = timestamp;
  }

  public boolean equalsTo(ValidityState otherState) {
    if (!(otherState instanceof TimestampValidityState)) {
      return false;
    }
    return myTimestamp == ((TimestampValidityState)otherState).myTimestamp;
  }

  public void save(DataOutputStream os) throws IOException {
    os.writeLong(myTimestamp);
  }

  public static TimestampValidityState load(DataInputStream is) throws IOException {
    return new TimestampValidityState(is.readLong());
  }
}
