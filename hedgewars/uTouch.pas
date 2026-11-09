(*
 * Hedgewars, a free turn based strategy game
 * Copyright (c) 2012 Richard Deurwaarder <xeli@xelification.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *)

{$INCLUDE "options.inc"}

unit uTouch;

interface

uses SysUtils, uUtils, uConsole, uVariables, SDLh, uFloat, uConsts, uCommands, GLUnit, uTypes, uCaptions, uWorld, uGearsHedgehog;


procedure initModule;
procedure freeModule;

procedure ProcessTouch;
procedure NewTurnBeginning;

procedure onTouchDown(x, y: Single; pointerId: Int64);
procedure onTouchMotion(x, y, dx, dy: Single; pointerId: Int64);
procedure onTouchUp(x, y: Single; pointerId: Int64);

function convertToCursorX(x: LongInt): LongInt;
function convertToCursorY(y: LongInt): LongInt;

function addFinger(x,y: Longword; id: Int64): PTouch_Data;
function updateFinger(x,y,dx,dy: LongInt; id: Int64): PTouch_Data;
procedure deleteFinger(id: Int64);

procedure onTouchClick(finger: TTouch_Data);
procedure onTouchDoubleClick(finger: TTouch_Data);
procedure onTouchLongClick(finger: TTouch_Data);

function findFinger(id: Int64): PTouch_Data;
procedure aim(finger: TTouch_Data);
function isOnCrosshair(finger: TTouch_Data): boolean;
function isOnCurrentHog(finger: TTouch_Data): boolean;
procedure convertToWorldCoord(var x,y: LongInt; finger: TTouch_Data);
procedure convertToFingerCoord(var x,y: LongInt; oldX, oldY: LongInt);
function fingerHasMoved(finger: TTouch_Data): boolean;
function calculateDelta(finger1, finger2: TTouch_Data): LongInt;
function getSecondFinger(finger: TTouch_Data): PTouch_Data;
function isOnRect(rect: TSDL_Rect; finger: TTouch_Data): boolean;
function isOnRect(x,y,w,h: LongInt; finger: TTouch_Data): boolean;
function isOnUIRect(rect: TSDL_Rect; finger: TTouch_Data; scale: GLfloat): boolean;
function isOnWidget(widget: TOnScreenWidget; finger: TTouch_Data): boolean;
procedure printFinger(finger: TTouch_Data);
implementation

const
    clickTime = 200;
    cMaxFingers = 10;
    noFingerId = -1; // no finger
    baseRectSize = 96;

var
    rectSize, halfRectSize, crosshairHalfSize: LongInt;

    pointerCount : Longword;
    fingers: array[0..cMaxFingers-1] of TTouch_Data;
    moveCursor : boolean;
    invertCursor : boolean;
    uiDragX, uiDragY: Single;

    //Pinch to zoom
    pinchSize : LongInt;
    baseZoomValue: GLFloat;
    switcherShown: boolean;
    lastTouchHog: PHedgehog;

    //aiming
    aimingCrosshair: boolean;
    aimingUp, aimingDown: boolean;
    targetAngle: LongInt;

    buttonsDown: Longword;
    targetting, targetted: boolean; //true when targetting an airstrike or the like

// ParseTeamCommand reads CurrentHedgehog^.BotLevel; a team can be removed or the game can end while a finger is
// down, leaving no current hedgehog. A touch then does nothing instead of dereferencing nil.
procedure TouchTeamCommand(s: shortstring);
begin
if (CurrentTeam <> nil) and (CurrentHedgehog <> nil) then
    ParseTeamCommand(s);
end;

procedure onTouchDown(x, y: Single; pointerId: Int64);
var
    finger: PTouch_Data;
    xr, yr: LongInt;
    tmp: LongWord;
begin
rectSize:= round(baseRectSize * cUIScale);
halfRectSize:= rectSize shr 1;
crosshairHalfSize:= round(baseRectSize * sqrt(cUIScale)) shr 1;
uiDragX:= 0;
uiDragY:= 0;
xr:= round(x * cScreenWidth);
yr:= round(y * cScreenHeight);

finger:= addFinger(xr, yr, pointerId);
finger^.downWhilePaused:= isPaused;

inc(buttonsDown);//inc buttonsDown, if we don't see a button down we'll dec it

// a phone that only watches a network game has no controls: every touch pans or zooms the camera
if ((not isPaused) or isOnWidget(pauseButton, finger^)) and ((GameType <> gmtNet) or (not cNetWatch)) then
begin
if isOnWidget(fireButton, finger^) then
    begin
    TouchTeamCommand('+attack');
    moveCursor:= false;
    finger^.pressedWidget:= @fireButton;
    exit;
    end;
if isOnWidget(arrowLeft, finger^) then
    begin
    TouchTeamCommand('+left');
    moveCursor:= false;
    finger^.pressedWidget:= @arrowLeft;
    exit;
    end;
if isOnWidget(arrowRight, finger^) then
    begin
    TouchTeamCommand('+right');
    if (CurrentHedgehog <> nil) and (CurrentHedgehog^.Gear <> nil) then WriteLnToConsole('[Touch] +right, hedgehog x ' + inttostr(hwRound(CurrentHedgehog^.Gear^.X)));
    moveCursor:= false;
    finger^.pressedWidget:= @arrowRight;
    exit;
    end;
if isOnWidget(arrowUp, finger^) then
    begin
    TouchTeamCommand('+up');
    aimingUp:= true;
    moveCursor:= false;
    finger^.pressedWidget:= @arrowUp;
    exit;
    end;
if isOnWidget(arrowDown, finger^) then
    begin
    TouchTeamCommand('+down');
    aimingDown:= true;
    moveCursor:= false;
    finger^.pressedWidget:= @arrowDown;
    exit;
    end;

// A network game cannot pause locally (the pause command only toggles away-from-keyboard there): the button is drawn
// for the host only, and asks the launcher/relay to pause or resume everybody; leaving goes through the Back key.
if isOnWidget(pauseButton, finger^) then
    begin
    if GameType <> gmtNet then
        begin
        WriteLnToConsole('[Touch] pressed pause');
        ParseCommand('pause', true);
        end
    else if cNetHost then
        begin
        WriteLnToConsole('[Touch] host asks for pause/resume');
        if isPaused then
            ParseCommand('netpausereq 0', true)
        else
            ParseCommand('netpausereq 1', true);
        end;
    // Where the button is not usable the press is still consumed, so a tap in the old pause corner is not a map press or click.
    moveCursor:= false;
    finger^.pressedWidget:= @pauseButton;
    exit;
    end;

if isOnWidget(utilityWidget, finger^) then
    begin
    finger^.pressedWidget:= @utilityWidget;
    moveCursor:= false;
    if(CurrentHedgehog <> nil) then
        begin
        if (CurrentHedgehog^.Gear <> nil) and (Ammoz[CurrentHedgehog^.CurAmmoType].Ammo.Propz and ammoprop_Timerable <> 0) then
            begin
            tmp:= HHGetTimerMsg(CurrentHedgehog^.Gear);
            if tmp <> MSGPARAM_INVALID then
                TouchTeamCommand('/timer ' + inttostr(tmp mod 5 + 1));
            end;
        end;
    exit;
    end;

if isOnWidget(utilityWidget2, finger^) then
    begin
    finger^.pressedWidget:= @utilityWidget2;
    moveCursor:= false;
    if(CurrentHedgehog <> nil) then
        begin
        if (CurrentHedgehog^.Gear <> nil) and (Ammoz[CurrentHedgehog^.CurAmmoType].Ammo.Propz and ammoprop_SetBounce <> 0) then
            begin
            tmp := HHGetBouncinessMsg(CurrentHedgehog^.Gear);
            if tmp <> MSGPARAM_INVALID then
                begin
                TouchTeamCommand('+precise');
                TouchTeamCommand('timer ' + inttostr(tmp mod 5 + 1));
                bounceButtonPressed:= true;
                end;
            end;
        end;
    exit;
    end;

// Only after all the widgets: the aiming marker follows the hedgehog and must never steal a touch
// from a button that is drawn over it. It is also only touchable while it is drawn.
if isOnCrosshair(finger^) then
    begin
    aimingCrosshair:= true;
    WriteLnToConsole('[Touch] aim marker grabbed');
    aim(finger^);
    moveCursor:= false;
    exit;
    end;

end; // not paused (while paused only the pause button reacts, the rest pans the camera)

dec(buttonsDown);//no buttonsDown, undo the inc() above
if buttonsDown = 0 then
    begin
    moveCursor:= true;
    case pointerCount of
        1:
            targetting:= not(targetted) and (CurrentHedgehog <> nil) and (Ammoz[CurrentHedgehog^.CurAmmoType].Ammo.Propz and ammoprop_NeedTarget <> 0)
                and ((GameType <> gmtNet) or (not cNetWatch));
        2:
            begin
            moveCursor:= false;
            pinchSize := calculateDelta(finger^, getSecondFinger(finger^)^);
            baseZoomValue := ZoomValue
            end;
        end;
    end;
end;

procedure onTouchMotion(x, y, dx, dy: Single; pointerId: Int64);
var
    finger, secondFinger: PTouch_Data;
    currentPinchDelta, zoom : Single;
    xr, yr, dxr, dyr: LongInt;
begin
xr:= round(x * cScreenWidth);
yr:= round(y * cScreenHeight);
dxr:= round(dx * cScreenWidth);
dyr:= round(dy * cScreenHeight);

finger:= updateFinger(xr, yr, dxr, dyr, pointerId);
if finger = nil then
    exit;

if moveCursor then
    begin
        if bShowAmmoMenu then
            begin
            // the weapon menu is drawn in UI units, so its cursor moves by UI units
            uiDragX:= uiDragX + finger^.dx / cMenuScale;
            uiDragY:= uiDragY + finger^.dy / cMenuScale;
            CursorPoint.X:= CursorPoint.X + trunc(uiDragX);
            CursorPoint.Y:= CursorPoint.Y - trunc(uiDragY);
            uiDragX:= uiDragX - trunc(uiDragX);
            uiDragY:= uiDragY - trunc(uiDragY);
            end
        else
        if invertCursor then
        begin
            CursorPoint.X := CursorPoint.X - finger^.dx;
            CursorPoint.Y := CursorPoint.Y + finger^.dy;
        end
    else
        begin
            CursorPoint.X := CursorPoint.X + finger^.dx;
            CursorPoint.Y := CursorPoint.Y - finger^.dy;
        end;
        exit //todo change into switch rather than ugly ifs
    end;

if aimingCrosshair then
    begin
        aim(finger^);
        exit
    end;

if (buttonsDown = 0) and (pointerCount = 2) then
    begin
       secondFinger := getSecondFinger(finger^);
       currentPinchDelta := calculateDelta(finger^, secondFinger^) - pinchSize;
       zoom := currentPinchDelta/cScreenWidth;
       ZoomValue := baseZoomValue - (zoom * cMinMaxZoomLevelDelta);
       if ZoomValue < cMaxZoomLevel then
           ZoomValue := cMaxZoomLevel;
       if ZoomValue > cMinZoomLevel then
           ZoomValue := cMinZoomLevel;
    end;

end;

procedure onTouchUp(x,y: Single; pointerId: Int64);
var
    finger: PTouch_Data;
    widget: POnScreenWidget;
    xr, yr: LongInt;
begin
xr:= round(x * cScreenWidth);
yr:= round(y * cScreenHeight);

finger:= updateFinger(xr, yr, 0, 0, pointerId);
if finger = nil then
    exit;

//Check for onTouchClick event
if not(fingerHasMoved(finger^)) then
    begin
    // While paused nothing is clicked: a tap that went down on no widget during the pause resumes the
    // game (and is not also a click), and a finger that went down before the pause (map, jump, menu
    // chest) or on a widget does nothing when lifted, so it cannot open the weapon menu or start a
    // long press jump behind the pause. The pause button finger is never a click either.
    if isPaused then
        begin
        if finger^.downWhilePaused and (finger^.pressedWidget = nil) then
            begin
            if GameType <> gmtNet then
                ParseCommand('pause', true)
            else
                ParseCommand('netpausereq 0', true);   // only the host's engine passes this on
            end;
        end
    else if finger^.pressedWidget <> @pauseButton then
        begin
        if (RealTicks - finger^.timeSinceDown) < clickTime then
            onTouchClick(finger^)
        else
            onTouchLongClick(finger^);
        end;
    end;

if aimingCrosshair then
    begin
    aimingCrosshair:= false;
    TouchTeamCommand('-up');
    TouchTeamCommand('-down');
    dec(buttonsDown);
    end;

widget:= finger^.pressedWidget;
if (buttonsDown > 0) and (widget <> nil) then
    begin
    dec(buttonsDown);

    // test aid: one line per released control, so the host tap test can see which one reacted
    if widget = @arrowLeft then WriteLnToConsole('[Touch] released arrowLeft')
    else if widget = @arrowRight then WriteLnToConsole('[Touch] released arrowRight')
    else if widget = @arrowUp then WriteLnToConsole('[Touch] released arrowUp')
    else if widget = @arrowDown then WriteLnToConsole('[Touch] released arrowDown')
    else if widget = @fireButton then WriteLnToConsole('[Touch] released fire')
    else if widget = @utilityWidget then WriteLnToConsole('[Touch] released utility')
    else if widget = @utilityWidget2 then WriteLnToConsole('[Touch] released utility2');

    if widget = @arrowLeft then
        TouchTeamCommand('-left');

    if widget = @arrowRight then
        begin
        TouchTeamCommand('-right');
        if (CurrentHedgehog <> nil) and (CurrentHedgehog^.Gear <> nil) then WriteLnToConsole('[Touch] -right, hedgehog x ' + inttostr(hwRound(CurrentHedgehog^.Gear^.X)));
        end;

    if widget = @arrowUp then
        TouchTeamCommand('-up');

    if widget = @arrowDown then
        TouchTeamCommand('-down');

    if widget = @fireButton then
        TouchTeamCommand('-attack');

    if widget = @utilityWidget then
        if (CurrentHedgehog <> nil)then
            if (CurAmmoGear <> nil) and (CurAmmoGear^.Kind = gtSwitcher) then
                begin
                WriteLnToConsole('[Touch] switch, hedgehog ' + CurrentHedgehog^.Name);
                TouchTeamCommand('switch');
                end
            else if(Ammoz[CurrentHedgehog^.CurAmmoType].Ammo.Propz and ammoprop_NeedTarget <> 0)then
                begin
                TouchTeamCommand('put');
                targetted:= true;
                end
            else if (CurAmmoGear <> nil) and (CurAmmoGear^.AmmoType = amSwitch) then
                TouchTeamCommand('switch')
            else WriteLnToConsole(inttostr(ord(Ammoz[CurrentHedgehog^.CurAmmoType].NameId)) + ' ' + inttostr(ord(sidSwitch)));
    end;

if targetting then
    AddCaption(trmsg[sidPressTarget], capcolDefault, capgrpAmmoInfo);

deleteFinger(pointerId);
end;

procedure onTouchDoubleClick(finger: TTouch_Data);
begin
finger := finger;//avoid compiler hint
end;

procedure onTouchLongClick(finger: TTouch_Data);
begin
if (GameType = gmtNet) and cNetWatch then exit;
if isOnWidget(jumpWidget, finger) then
    begin
    TouchTeamCommand('ljump');
    exit;
    end;
end;

procedure onTouchClick(finger: TTouch_Data);
begin
if (GameType = gmtNet) and cNetWatch then exit;

if bShowAmmoMenu then
    begin
    if isOnUIRect(AmmoRect, finger, cMenuScale) then
        begin
        CursorPoint.X:= round(finger.x / cMenuScale);
        CursorPoint.Y:= round(finger.y / cMenuScale);
        TouchTeamCommand('put');
        end
    else
        bShowAmmoMenu:= false;
    exit;
    end;

if isOnCurrentHog(finger) or isOnWidget(AMWidget, finger) then
    begin
    if isOnWidget(AMWidget, finger) then WriteLnToConsole('[Touch] tapped weapon chest');
    bShowAmmoMenu := true;
    exit;
    end;

if isOnWidget(jumpWidget, finger) then
    begin
    WriteLnToConsole('[Touch] tapped jump');
    TouchTeamCommand('hjump');
    exit;
    end;
end;

function addFinger(x,y: Longword; id: Int64): PTouch_Data;
var
    xCursor, yCursor, index : LongInt;
begin
// check array size
// note: pointerCount will be incremented later,
// so at this point it's the index of the new entry
// fixed size under pas2c (no dynamic arrays); extra fingers are ignored by reusing the last slot
if pointerCount >= cMaxFingers then
    pointerCount:= cMaxFingers - 1;

xCursor := convertToCursorX(x);
yCursor := convertToCursorY(y);

//on removing fingers, all fingers are moved to the left
//with dynamic arrays being zero based, the new position of the finger is the old pointerCount
fingers[pointerCount].id := id;
fingers[pointerCount].historicalX := xCursor;
fingers[pointerCount].historicalY := yCursor;
fingers[pointerCount].x := xCursor;
fingers[pointerCount].y := yCursor;
fingers[pointerCount].dx := 0;
fingers[pointerCount].dy := 0;
fingers[pointerCount].timeSinceDown:= RealTicks;
fingers[pointerCount].pressedWidget:= nil;
fingers[pointerCount].downWhilePaused:= false;

addFinger:= @fingers[pointerCount];
inc(pointerCount);
end;

function updateFinger(x, y, dx, dy: LongInt; id: Int64): PTouch_Data;
var finger : PTouch_Data;
begin
finger:= findFinger(id);

if finger <> nil then
    begin
    finger^.x:= convertToCursorX(x);
    finger^.y:= convertToCursorY(y);
    finger^.dx:= dx;
    finger^.dy:= dy;
    end
else
    WriteLnToConsole('finger ' + inttostr(id) + ' not found');
updateFinger:= finger
end;

procedure deleteFinger(id: Int64);
var
    index : Longword;
begin
if pointerCount = 0 then
    exit;
dec(pointerCount);
for index := 0 to pointerCount do
    begin
    if fingers[index].id = id then
        begin
        //put the last finger into the spot of the finger to be removed,
        //so that all fingers are packed to the far left
        if pointerCount <> index then
            begin
            fingers[index].id := fingers[pointerCount].id;
            fingers[index].x := fingers[pointerCount].x;
            fingers[index].y := fingers[pointerCount].y;
            fingers[index].historicalX := fingers[pointerCount].historicalX;
            fingers[index].historicalY := fingers[pointerCount].historicalY;
            fingers[index].timeSinceDown := fingers[pointerCount].timeSinceDown;
            fingers[index].pressedWidget := fingers[pointerCount].pressedWidget;
            fingers[index].downWhilePaused := fingers[pointerCount].downWhilePaused;

            fingers[pointerCount].id := noFingerId;
            end
        else
            fingers[index].id := noFingerId;
        break;
        end;
    end;

end;

procedure NewTurnBeginning;
begin
targetted:= false;
targetting:= false;
cTurnCamPending:= true;
SetUtilityWidgetState(amNothing);
end;

// The turn-start switcher (scheme flag "switch hedgehog at turn start") is a gear, not a selected weapon, so the
// switch button has to follow the gear: shown while the switcher is there, hidden when it ends (any other action).
procedure UpdateSwitchButton;
var active: boolean;
begin
active:= (CurrentHedgehog <> nil) and (CurAmmoGear <> nil) and (CurAmmoGear^.Kind = gtSwitcher);
if active <> switcherShown then
    begin
    switcherShown:= active;
    SetUtilityWidgetState(amNothing);
    if active then
        WriteLnToConsole('[Touch] switch button shown, hedgehog ' + CurrentHedgehog^.Name)
    else
        WriteLnToConsole('[Touch] switch button hidden');
    end;
if (CurrentHedgehog <> lastTouchHog) then
    begin
    lastTouchHog:= CurrentHedgehog;
    if CurrentHedgehog <> nil then
        WriteLnToConsole('[Touch] active hedgehog ' + CurrentHedgehog^.Name);
    end;
end;


procedure ProcessTouch;
var
    deltaAngle: LongInt;
begin
UpdateSwitchButton;
invertCursor := not(bShowAmmoMenu or targetting);
if aimingCrosshair and ((CurrentHedgehog = nil) or (CurrentHedgehog^.Gear = nil)) then
    begin
    // the hedgehog is gone (team removed, game over) while the marker is held: stop steering. The flag stays until the
    // finger lifts, so the finger count in buttonsDown remains balanced.
    aimingUp:= false;
    aimingDown:= false;
    end
else
if aimingCrosshair then
    if CurrentHedgehog^.Gear <> nil then
        begin
        deltaAngle:= CurrentHedgehog^.Gear^.Angle - targetAngle;
        if (deltaAngle > -5) and (deltaAngle < 5) then
            begin
                if(aimingUp)then
                    begin
                    aimingUp:= false;
                    TouchTeamCommand('-up');
                    end;
                if(aimingDown)then
                    begin
                    aimingDown:= false;
                    TouchTeamCommand('-down');
                    end
            end
        else
            begin
            if (deltaAngle < 0) then
                begin
                if aimingUp then
                    begin
                    aimingUp:= false;
                    TouchTeamCommand('-up');
                    end;
                if(aimingDown)then
                    begin
                    aimingDown:= true;
                    TouchTeamCommand('-down');
                    end
                end
            else
                begin
                if aimingDown then
                    begin
                    TouchTeamCommand('-down');
                    aimingDown:= false;
                    end;
                if aimingUp then
                    begin
                    aimingUp:= true;
                    TouchTeamCommand('+up');
                    end;
                end;
            end;
        end
    else
        begin
        if aimingUp then
            begin
            TouchTeamCommand('-up');
            aimingUp:= false;
            end;
        if aimingDown then
            begin
            TouchTeamCommand('-down');
            aimingDown:= false;
            end;
        end;

if bounceButtonPressed then
    begin
    TouchTeamCommand('-precise');
    bounceButtonPressed:= false;
    end;
end;

function findFinger(id: Int64): PTouch_Data;
var
    index: LongWord;
begin
    for index:= 0 to (cMaxFingers-1) do
        if fingers[index].id = id then
            begin
            findFinger:= @fingers[index];
            exit;
            end;
    findFinger:= nil;
end;

procedure aim(finger: TTouch_Data);
var
    hogX, hogY, touchX, touchY, deltaX, deltaY: LongInt;
begin
    if (CurrentHedgehog <> nil) and (CurrentHedgehog^.Gear <> nil) then
        begin
        touchX := 0;//avoid compiler hint
        touchY := 0;
        hogX := hwRound(CurrentHedgehog^.Gear^.X);
        hogY := hwRound(CurrentHedgehog^.Gear^.Y);

        convertToWorldCoord(touchX, touchY, finger);
        deltaX := abs(TouchX-HogX);
        deltaY := TouchY-HogY;

        if (deltaX <> 0) or (deltaY <> 0) then
            targetAngle:= (Round(DeltaY / sqrt(sqr(deltaX) + sqr(deltaY)) * 2048) + 2048) div 2;
        end; //if CurrentHedgehog^.Gear <> nil
end;

// These 4 convertToCursor functions convert xy coords from the SDL coordinate system to our CursorPoint coor system:
// - the SDL coordinate system is proportional to the screen and values are normalized in the onTouch* functions
// - the CursorPoint coordinate system goes from -cScreenWidth/2 to cScreenWidth/2 on the x axis
//   and 0 to cScreenHeight on the x axis, (-cScreenWidth, cScreenHeight) being top left.
function convertToCursorX(x: LongInt): LongInt;
begin
    convertToCursorX:= x - cScreenWidth shr 1;
end;

function convertToCursorY(y: LongInt): LongInt;
begin
    convertToCursorY:= cScreenHeight - y;
end;

// True when the finger is within half a side of the point (worldX, worldY) of the world, as drawn now.
function isNearWorldPoint(worldX, worldY, half: LongInt; finger: TTouch_Data): boolean;
var
    x, y: LongInt;
begin
    x:= 0;
    y:= 0;
    convertToFingerCoord(x, y, worldX, worldY);
    isNearWorldPoint:= (abs(finger.x - x) < half) and (abs(finger.y - y) < half);
end;

// The aiming marker can only be grabbed while it is drawn (see RenderHHGuiExtras). Its touch area follows the
// size of a finger rather than the whole UI scale, so it does not cover the buttons next to a low hedgehog.
function isOnCrosshair(finger: TTouch_Data): boolean;
begin
    isOnCrosshair:= false;
    if (CurrentHedgehog = nil) or (CurrentHedgehog^.Gear = nil) or (CrosshairGear = nil) or
       (CrosshairGear <> CurrentHedgehog^.Gear) then
        exit;
    isOnCrosshair:= isNearWorldPoint(CrosshairX, CrosshairY, crosshairHalfSize, finger);
end;

function isOnCurrentHog(finger: TTouch_Data): boolean;
begin
    isOnCurrentHog:= false;
    if CurrentHedgehog = nil then exit;
    if CurrentHedgehog^.Gear = nil then exit;
    isOnCurrentHog:= isNearWorldPoint(hwRound(CurrentHedgehog^.Gear^.X), hwRound(CurrentHedgehog^.Gear^.Y), halfRectSize, finger);
end;

// Finger coordinates are centred horizontally and count up from the bottom (see convertToCursorX/Y). The world
// is drawn about the middle of the screen at the scale 'zoom' (see SetScale), so both directions go through it.
procedure convertToFingerCoord(var x, y : LongInt; oldX, oldY: LongInt);
var
    k: Single;
begin
    k:= zoom / 2;
    x:= round((oldX + WorldDx) * k);
    y:= cScreenHeight - round(cScreenHeight / 2 + ((oldY + WorldDy) - cScreenHeight / 2) * k);
end;

procedure convertToWorldCoord(var x,y: LongInt; finger: TTouch_Data);
var
    k: Single;
begin
    k:= 2 / zoom;
    x:= round(finger.x * k) - WorldDx;
    y:= round(cScreenHeight / 2 + ((cScreenHeight - finger.y) - cScreenHeight / 2) * k) - WorldDy;
end;

//Method to calculate the distance this finger has moved since the downEvent
function fingerHasMoved(finger: TTouch_Data): boolean;
begin
    fingerHasMoved := trunc(sqrt(sqr(finger.X-finger.historicalX) + sqr(finger.y-finger.historicalY))) > 30;
end;

function calculateDelta(finger1, finger2: TTouch_Data): LongInt; 
begin
    calculateDelta := Round(sqrt(sqr(finger2.x-finger1.x) + sqr(finger2.y-finger1.y)));
end;

// Under the premise that all pointer ids in pointerIds:Int64 are packed to the far left.
// If the pointer to be ignored is not pointerIds[0] the second must be there
function getSecondFinger(finger: TTouch_Data): PTouch_Data;
begin
    if fingers[0].id = finger.id then
        getSecondFinger := @fingers[1]
    else
        getSecondFinger := @fingers[0];
end;

function isOnRect(rect: TSDL_Rect; finger: TTouch_Data): boolean;
begin
    isOnRect:= isOnRect(rect.x, rect.y, rect.w, rect.h, finger);
end;

function isOnRect(x,y,w,h: LongInt; finger: TTouch_Data): boolean;
begin
    isOnRect:= (finger.x > x)   and
               (finger.x < x + w) and
               (cScreenHeight - finger.y > y) and
               (cScreenHeight - finger.y < y + h);
end;

// Hit test for things drawn in UI units (the touch HUD): see UISwapIn in uWorld.
function isOnUIRect(rect: TSDL_Rect; finger: TTouch_Data; scale: GLfloat): boolean;
var fx, fy: LongInt;
begin
    fx:= round(finger.x / scale);
    fy:= round((cScreenHeight - finger.y) / scale);
    isOnUIRect:= (fx > rect.x) and (fx < rect.x + rect.w) and
                 (fy > rect.y) and (fy < rect.y + rect.h);
end;

function isOnWidget(widget: TOnScreenWidget; finger: TTouch_Data): boolean;
begin
    isOnWidget:= widget.show and isOnUIRect(widget.active, finger, cUIScale);
end;

procedure printFinger(finger: TTouch_Data);
begin
    WriteLnToConsole('id: ' + IntToStr(finger.id) + ', x: ' + IntToStr(finger.x) + ' y: ' + IntToStr(finger.y) +
                     ' (rel x: ' + IntToStr(finger.historicalX) + ' rel y: ' + IntToStr(finger.historicalY) +
                     '), time: ' + IntToStr(finger.timeSinceDown));
end;

procedure initModule;
var
    index: Longword;
begin
    switcherShown:= false;
    lastTouchHog:= nil;
    buttonsDown:= 0;
    pointerCount:= 0;
    bounceButtonPressed:= false;

    for index := 0 to (cMaxFingers-1) do
        fingers[index].id := noFingerId;

    uiDragX:= 0;
    uiDragY:= 0;
    rectSize:= round(baseRectSize * cUIScale);
    halfRectSize:= rectSize shr 1;
    crosshairHalfSize:= round(baseRectSize * sqrt(cUIScale)) shr 1;
end;

procedure freeModule;
begin
end;

end.
