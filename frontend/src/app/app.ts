import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Root component — just a router-outlet; all layout lives in ShellComponent. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />'
})
export class App {}
