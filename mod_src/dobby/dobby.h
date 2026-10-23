//
// Dobby - a lightweight inline hooking library for Android.
//
// The mod menus (LGL / R3DNETWORK) hook the game with it: it writes a
// trampoline and fixes up the PC relative instructions of the prologue
// (ADRP/ADR), which the "restore the bytes and call the original" trick
// cannot do.
//
// The prebuilt arm64 static library is fetched by the CI into
// mod_src/dobby/ (see the workflow), the sources are on
// https://github.com/jmpews/Dobby (Apache-2.0).
//

#ifndef DODBY_H_
#define DODBY_H_

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------
// DobbyHook
//   address        : the target function
//   replace_call   : the replacement function
//   origin_call    : where the original function is stored
// ---------------------------------------------------------------------
bool DobbyHook(void *address, void *replace_call, void **origin_call);

// DobbyDestroy
bool DobbyDestroy(void *address);

// DobbyInstrument
//   callback        : a function like "void (*callback)(void *context)"
//   address         : the target function
//   replace_call    : the replacement function
//   origin_call     : where the original function is stored
bool DobbyInstrument(void *address, void *replace_call, void **origin_call);

#ifdef __cplusplus
}
#endif

#endif // DODBY_H_
