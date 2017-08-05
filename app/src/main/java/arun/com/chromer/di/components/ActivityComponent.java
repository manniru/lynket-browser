/*
 * Chromer
 * Copyright (C) 2017 Arunkumar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package arun.com.chromer.di.components;

import android.support.annotation.NonNull;

import arun.com.chromer.MainActivity;
import arun.com.chromer.activities.blacklist.BlacklistManagerActivity;
import arun.com.chromer.di.PerActivity;
import arun.com.chromer.di.modules.ActivityModule;
import arun.com.chromer.di.modules.FragmentModule;
import dagger.Subcomponent;

@PerActivity
@Subcomponent(modules = {
        ActivityModule.class
})
public interface ActivityComponent {
    @NonNull
    FragmentComponent newFragmentComponent(@NonNull FragmentModule fragmentModule);

    void inject(BlacklistManagerActivity blacklistManagerActivity);

    void inject(MainActivity mainActivity);
}