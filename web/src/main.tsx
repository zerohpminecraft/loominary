import { render } from 'preact';
import { App } from './App.js';
import { initAnalytics } from './analytics.js';

initAnalytics();

render(<App />, document.getElementById('app')!);
