//
// Created by jetbrains on 08/08/2018.
//

#include <utility.h>

// TODO: wrap with ifdef
constexpr bool DEBUG_MODE = true;

raw_ostream &logging::info() {
  return outs();
}
raw_ostream &logging::debug() {
  if (DEBUG_MODE) {
    return outs();
  } else {
    return nulls();
  }
}
raw_ostream &logging::error() {
  return errs();
}
